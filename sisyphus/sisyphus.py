import pika
import docker
from confluent_kafka import Producer, Consumer, KafkaError

DEFAULT_HOST = 'localhost'
DEFAULT_QUEUE = 'tasks'

class Sisyphus(object):
	def __init__(self, id, config):
		self.id = id
		self.config = config
		self.running = False

		self.rabbit_parameters = pika.ConnectionParameters(host=config['rabbit'].get('host', DEFAULT_HOST))
		self.rabbit_connection = pika.BlockingConnection(self.rabbit_parameters)
		self.rabbit_queue = config['rabbit'].get('queue', DEFAULT_QUEUE)
		self.rabbit = connection.channel()

		this = self
		def rabbit_callback(ch, method, properties, body):
			print(body)

		self.rabbit.queue_declare(queue=self.rabbit_queue, durable=True)
		self.rabbit.basic_qos(prefetch_count=1)
		self.rabbit.basic_consume(queue=self.rabbit_queue, on_message_callback=rabbit_callback)

		self.producer = Producer({
			'bootstrap.servers': self.config['kafka']['host']})

		self.consumer = None
		if self.config['kafka']['subscribe']:
			self.consumer = Consumer({
				'bootstrap.servers': self.config['kafka']['host'],
				'enable.auto.commit': True,
				'group.id': 'sisyphus-' + str(id),
				'default.topic.config': {
					'auto.offset.reset': 'latest'}})

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
		
