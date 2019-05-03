import os
import sys
import pika
import json
import docker
import traceback
from pathlib import Path
from google.cloud import storage
from confluent_kafka import Producer, Consumer, KafkaError

DEFAULT_HOST = 'localhost'
DEFAULT_QUEUE = 'tasks'
DEFAULT_ROOT = '/tmp/sisyphus'

def print_exception():
	typ, value, trace = sys.exc_info()
	print(typ)
	print(value)
	traceback.print_tb(trace)

def setup_rabbit(config):
	parameters = pika.ConnectionParameters(host=config.get('host', DEFAULT_HOST))
	connection = pika.BlockingConnection(parameters)
	channel = connection.channel()
	queue = config.get('queue', DEFAULT_QUEUE)
	channel.queue_declare(queue=queue, durable=True)

	return {
		'parameters': parameters,
		'connection': connection,
		'channel': channel,
		'queue': queue}

def publish(rabbit, message):
	rabbit['channel'].basic_publish(
		exchange='',
		routing_key=rabbit['queue'],
		body=json.dumps(message),
		properties=pika.BasicProperties(delivery_mode=2))

class Sisyphus(object):
	def __init__(self, id, config={}):
		self.id = id
		self.config = config
		self.running = False

		self.setup_rabbit(config.get('rabbit', {}))
		self.setup_docker(config.get('docker', {}))
		self.setup_kafka(config.get('kafka', {}))
		self.setup_storage(config.get('storage', {}))

	def setup_rabbit(self, config):
		rabbit = setup_rabbit(config)

		self.rabbit_parameters = rabbit['parameters']
		self.rabbit_connection = rabbit['connection']
		self.rabbit = rabbit['channel']
		self.rabbit_queue = rabbit['queue']

		this = self
		def rabbit_callback(ch, method, properties, body):
			try:
				task = json.loads(body.decode('utf-8'))
				this.perform(task)
			except Exception:
				print_exception()

			ch.basic_ack(delivery_tag=method.delivery_tag)

		self.rabbit.basic_qos(prefetch_count=1)
		self.rabbit.basic_consume(queue=self.rabbit_queue, on_message_callback=rabbit_callback)

	def setup_docker(self, config):
		self.docker = docker.from_env()

	def setup_kafka(self, config):
		self.producer = Producer({
			'bootstrap.servers': self.config.get('host', 'localhost')})

		self.consumer = None
		if self.config.get('subscribe'):
			self.consumer = Consumer({
				'bootstrap.servers': self.config.get('host', 'localhost'),
				'enable.auto.commit': True,
				'group.id': 'sisyphus-' + str(id),
				'default.topic.config': {
					'auto.offset.reset': 'latest'}})

	def setup_storage(self, config):
		self.storage = storage.Client()
		self.bucket_name = config.get('bucket', 'sisyphus')
		self.bucket = self.storage.get_bucket(self.bucket_name)
		self.local_root = config.get('local_root', DEFAULT_ROOT)

	def preinitialize(self):
		pass

	def start(self):
		"""
		Start the polling loop if we have a consumer, otherwise just call `initialize` directly
		"""

		if self.consumer:
			topics = self.config['kafka']['subscribe']
			self.consumer.subscribe(topics)

			self.poll()
		else:
			self.preinitialize()
			self.initialized = True
			self.rabbit.start_consuming()

	def poll(self):
		"""
		Enter the main consumer polling loop.

		Once poll is called, the thread will be claimed and any interaction with the 
		system from this point on will be mediated through message passing. This is called
		at the end of the base class's `__init__(agent_id, kafka_config)` method and does not
		need to be called manually by the subclass.
		"""

		self.running = True
		while self.running:
			raw = self.consumer.poll(timeout=1.0)  # timeout (in seconds) so ^C works

			# calling initialize() once consumer is established so as not to miss
			# immediate responses to initialization sends. If `poll` is not called before an
			# initialization message is sent then an immediate response could be missed.
			if not self.initialized:
				self.preinitialize()
				self.initialized = True

			if raw is None:
				continue
			if raw.error():
				if raw.error().code() == KafkaError._PARTITION_EOF:
					continue
				else:
					print('Error in kafka consumer:', raw.error())

					self.running = False

			else:
				# `raw.value()` is implemented in C with a docstring that
				# suggests it needs a `payload` argument. Suppress the warning.
				# noinspection PyArgumentList
				message = json.loads(raw.value())
				if not message:
					continue

				if message['event'] == event.GLOBAL_SHUTDOWN:
					self.shutdown()
				else:
					self.receive(raw.topic(), message)

	def print_message(self, topic, message, incoming=True):
		print('{} {} {}'       # <-- topic event
			  ' [{} {}]:'      # [agent_type agent_id]
			  ' {}'.format(  # {message dict} + 2 BLOBs
			'-->' if incoming else '<--',
			topic,
			message.get('event', 'generic'),

			self.agent_type,
			self.agent_id,

			message))

	def send(self, topic, message, print_send=True):
		"""
		Send a Kafka message on the given topic.

		Args:
			topic (str): The Kafka topic to send the message on.

			message (dict): A dictionary containing the message to send. This dictionary
				needs to be JSON serializable, so it must contain only basic types like `str`,
				`int`, `float`, `list`, `tuple`, `array`, and `dict`. Any functions or objects
				present will throw errors.

		    print_send (bool): Whether or not to print the message that is sent.
		"""
		if print_send:
			self.print_message(topic, message, False)

		self.producer.produce(
			topic,
			json.dumps(message),
			callback=delivery_report)

		self.producer.flush(timeout=1.0)

	def receive(self, topic, message):
		self.print_message(topic, message, True)
		
	def upload(self, bucket, source, destination):
		blob = bucket.blob(destination)
		blob.upload_from_filename(source)

	def download(self, bucket, source, destination):
		blob = bucket.blob(source)
		blob.download_to_filename(destination)

	def parse_storage_key(self, key):
		parts = key.split(':')
		if len(parts) > 1:
			return parts[0], ':'.join(parts[1:])
		else:
			return self.bucket_name, parts[0]

	def setup_path(self, base, key):
		local_path = os.path.join(self.local_root, base, key)
		local_base, _ = os.path.split(local_path)
		os.makedirs(local_base, exist_ok=True)
		return local_path

	def perform(self, task):
		print('perform task: {}'.format(task))

		local_inputs = {}
		for remote, internal in task['inputs'].items():
			bucket_name, key = self.parse_storage_key(remote)
			bucket = self.storage.get_bucket(bucket_name)
			local_path = self.setup_path('inputs', key)
			local_inputs[local_path] = internal

			self.download(bucket, key, local_path)

		local_outputs = {}
		for remote, internal in task['outputs'].items():
			bucket_name, key = self.parse_storage_key(remote)
			local_path = self.setup_path('outputs', key)
			local_outputs[local_path] = internal

			Path(local_path).touch()

		volumes = {}
		for local, internal in local_inputs.items():
			volumes[local] = {
				'bind': internal,
				'mode': 'ro'}

		for local, internal in local_outputs.items():
			volumes[local] = {
				'bind': internal,
				'mode': 'rw'}

		self.docker.images.pull(task['container'])

		for command in task['commands']:
			print(command)
			tokens = command['command']
			if 'stdout' in command:
				tokens += ['>', command['stdout']]
				sub = ' '.join(tokens)
				tokens = ['sh', '-c', sub]

			self.docker.containers.run(
				task['container'],
				tokens,
				volumes=volumes)


if __name__ == '__main__':
	sisyphus = Sisyphus(1)
	print('sisyphus rises')
	sisyphus.start()
