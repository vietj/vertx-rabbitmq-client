package io.vertx.rabbitmq.impl;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.rabbitmq.QueueOptions;
import io.vertx.rabbitmq.RabbitMQConsumer;
import io.vertx.rabbitmq.RabbitMQMessage;

import static io.vertx.rabbitmq.impl.Utils.put;
import static io.vertx.rabbitmq.impl.Utils.toJson;

public class QueueConsumerHandler extends DefaultConsumer {

  private final RabbitMQConsumerImpl queue;
  private final boolean includeProperties;
  private final Context handlerContext;

  private static final Logger log = LoggerFactory.getLogger(ConsumerHandler.class);

  QueueConsumerHandler(Vertx vertx, Channel channel, boolean includeProperties, QueueOptions options) {
    super(channel);
    this.handlerContext = vertx.getOrCreateContext();
    this.includeProperties = includeProperties;
    this.queue = new RabbitMQConsumerImpl(handlerContext, this, options);
  }

  @Override
  public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) {
    RabbitMQMessage msg = new RabbitMQMessageImpl(body, consumerTag, envelope, properties);
    this.handlerContext.runOnContext(v -> queue.handleMessage(msg));
  }

  @Override
  public void handleCancel(String consumerTag) {
    log.debug("consumer has been cancelled unexpectedly");
    queue.handleEnd();
  }

  /**
   * @return a queue for message consumption
   */
  public RabbitMQConsumer queue() {
    return queue;
  }
}
