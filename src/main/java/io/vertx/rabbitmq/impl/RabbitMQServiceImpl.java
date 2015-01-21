package io.vertx.rabbitmq.impl;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.ShutdownListener;
import com.rabbitmq.client.ShutdownSignalException;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.impl.LoggerFactory;
import io.vertx.rabbitmq.RabbitMQService;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static io.vertx.rabbitmq.impl.Utils.*;

/**
 * @author <a href="mailto:nscavell@redhat.com">Nick Scavelli</a>
 */
public class RabbitMQServiceImpl implements RabbitMQService, ShutdownListener {

  private static final Logger log = LoggerFactory.getLogger(RabbitMQServiceImpl.class);

  private final Vertx vertx;
  private final JsonObject config;
  private final Integer retries;
  private final boolean includeProperites;

  private Connection connection;
  private Channel channel;

  public RabbitMQServiceImpl(Vertx vertx, JsonObject config) {
    this.vertx = vertx;
    this.config = config;
    this.retries = config.getInteger("connectionRetries", null);
    //TODO: includeProperties isn't really intuitive
    //TODO: Think about allowing this at a method level ?
    this.includeProperites = config.getBoolean("includeProperties", false);
  }

  @Override
  public void basicConsume(String queue, String address, Handler<AsyncResult<Void>> resultHandler) {
    forChannel(resultHandler, channel -> {
      channel.basicConsume(queue, new ConsumerHandler(vertx, channel, includeProperites, ar -> {
        if (ar.succeeded()) {
          vertx.eventBus().send(address, ar.result());
          resultHandler.handle(Future.succeededFuture());
        } else {
          log.error("Exception occurred inside rabbitmq service consumer.", ar.cause());
          resultHandler.handle(Future.failedFuture(ar.cause()));
        }
      }));
    });
  }

  @Override
  public void basicGet(String queue, boolean autoAck, Handler<AsyncResult<JsonObject>> resultHandler) {
    forChannel(resultHandler, channel -> {
      GetResponse response = channel.basicGet(queue, autoAck);
      if (response == null) {
        resultHandler.handle(Future.succeededFuture());
      } else {
        JsonObject json = new JsonObject();
        populate(json, response.getEnvelope());
        if (includeProperites) {
          put("properties", toJson(response.getProps()), json);
        }
        put("body", parse(response.getProps(), response.getBody()), json);
        put("messageCount", response.getMessageCount(), json);
        resultHandler.handle(Future.succeededFuture(json));
      }
    });
  }

  @Override
  public void basicPublish(String exchange, String routingKey, JsonObject message, Handler<AsyncResult<Void>> resultHandler) {
    forChannel(resultHandler, channel -> {
      //TODO: Really need an SPI / Interface to decouple this and allow pluggable implementations
      JsonObject properties = message.getJsonObject("properties");
      String contentType = properties == null ? null : properties.getString("contentType");
      String encoding = properties == null ? null : properties.getString("contentEncoding");
      byte[] body;
      if (contentType != null) {
        switch (contentType) {
          case "application/json":
            body = encode(encoding, message.getJsonObject("body").toString());
            break;
          case "application/octet-stream":
            body = message.getBinary("body");
            break;
          case "text/plain":
          default:
            body = encode(encoding, message.getString("body"));
        }
      } else {
        body = encode(encoding, message.getString("body"));
      }

      channel.basicPublish(exchange, routingKey, fromJson(properties), body);
      resultHandler.handle(Future.succeededFuture());
    });
  }

  @Override
  public void exchangeDeclare(String exchange, String type, boolean durable, boolean autoDelete, Handler<AsyncResult<Void>> resultHandler) {
    forChannel(resultHandler, channel -> {
      channel.exchangeDeclare(exchange, type, durable, autoDelete, null);
      resultHandler.handle(Future.succeededFuture());
    });
  }

  @Override
  public void exchangeDelete(String exchange, Handler<AsyncResult<Void>> resultHandler) {
    forChannel(resultHandler, channel -> {
      channel.exchangeDelete(exchange);
      resultHandler.handle(Future.succeededFuture());
    });
  }

  @Override
  public void exchangeBind(String destination, String source, String routingKey, Handler<AsyncResult<Void>> resultHandler) {
    forChannel(resultHandler, channel -> {
      channel.exchangeBind(destination, source, routingKey);
      resultHandler.handle(Future.succeededFuture());
    });
  }

  @Override
  public void exchangeUnbind(String destination, String source, String routingKey, Handler<AsyncResult<Void>> resultHandler) {
    forChannel(resultHandler, channel -> {
      channel.exchangeUnbind(destination, source, routingKey);
      resultHandler.handle(Future.succeededFuture());
    });
  }

  @Override
  public void queueDeclareAuto(Handler<AsyncResult<JsonObject>> resultHandler) {
    forChannel(resultHandler, channel -> {
      AMQP.Queue.DeclareOk result = channel.queueDeclare();
      resultHandler.handle(Future.succeededFuture(toJson(result)));
    });
  }

  @Override
  public void queueDeclare(String queue, boolean durable, boolean exclusive, boolean autoDelete, Handler<AsyncResult<JsonObject>> resultHandler) {
    forChannel(resultHandler, channel -> {
      AMQP.Queue.DeclareOk result = channel.queueDeclare(queue, durable, exclusive, autoDelete, null);
      resultHandler.handle(Future.succeededFuture(toJson(result)));
    });
  }

  @Override
  public void queueDelete(String queue, Handler<AsyncResult<JsonObject>> resultHandler) {
    forChannel(resultHandler, channel -> {
      AMQP.Queue.DeleteOk result = channel.queueDelete(queue);
      resultHandler.handle(Future.succeededFuture(toJson(result)));
    });
  }

  @Override
  public void queueDeleteIf(String queue, boolean ifUnused, boolean ifEmpty, Handler<AsyncResult<JsonObject>> resultHandler) {
    forChannel(resultHandler, channel -> {
      AMQP.Queue.DeleteOk result = channel.queueDelete(queue, ifUnused, ifEmpty);
      resultHandler.handle(Future.succeededFuture(toJson(result)));
    });
  }

  @Override
  public void queueBind(String queue, String exchange, String routingKey, Handler<AsyncResult<Void>> resultHandler) {
    forChannel(resultHandler, channel -> {
      channel.queueBind(queue, exchange, routingKey);
      resultHandler.handle(Future.succeededFuture());
    });
  }

  @Override
  public void start() {
    log.info("Starting rabbitmq service");
    try {
      connect();
    } catch (IOException e) {
      log.error("Could not connect to rabbitmq", e);
      if (retries != null) {
        try {
          reconnect();
        } catch (IOException ioex) {
          throw new RuntimeException(ioex);
        }
      } else {
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  public void stop() {
    log.info("Stopping rabbitmq service");
    try {
      disconnect();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private <T> void forChannel(Handler<AsyncResult<T>> resultHandler, ChannelHandler channelHandler) {
    if (connection == null || channel == null) {
      resultHandler.handle(Future.failedFuture("Not connected"));
      return;
    }
    if (!channel.isOpen()) {
      try {
        //TODO: Is this the best thing ?
        channel = connection.createChannel();
      } catch (IOException e) {
        resultHandler.handle(Future.failedFuture(e));
      }
    }
    try {
      channelHandler.handle(channel);
    } catch (Throwable t) {
      resultHandler.handle(Future.failedFuture(t));
    }
  }

  private void connect() throws IOException {
    log.debug("Connecting to rabbitmq...");
    connection = newConnection(config);
    connection.addShutdownListener(this);
    channel = connection.createChannel();
    log.debug("Connected to rabbitmq !");
  }

  private void disconnect() throws IOException {
    try {
      log.debug("Disconnecting from rabbitmq...");
      // This will close all channels related to this connection
      connection.close();
      log.debug("Disconnected from rabbitmq !");
    } finally {
      connection = null;
      channel = null;
    }
  }

  private void reconnect() throws IOException {
    if (retries == null) return;
    log.info("Attempting to reconnect to rabbitmq...");
    AtomicInteger attempts = new AtomicInteger(0);
    int retries = this.retries;
    long delay = config.getLong("connectionRetryDelay", 10000L); // Every 10 seconds by default
    vertx.setPeriodic(delay, id -> {
      int attempt = attempts.incrementAndGet();
      if (attempt == retries) {
        vertx.cancelTimer(id);
        log.info("Max number of connect attempts (" + retries + ") reached. Will not attempt to connect again");
      } else {
        try {
          log.debug("Reconnect attempt # " + attempt);
          connect();
          vertx.cancelTimer(id);
          log.info("Successfully reconnected to rabbitmq (attempt # " + attempt + ")");
        } catch (IOException e) {
          log.debug("Failed to connect attempt # " + attempt, e);
        }
      }
    });
  }

  @Override
  public void shutdownCompleted(ShutdownSignalException cause) {
    log.info("RabbitMQ connection shutdown !", cause);
    try {
      reconnect();
    } catch (IOException e) {
      log.error("IOException during reconnect.", e);
    }
  }

  private static Connection newConnection(JsonObject config) throws IOException {
    ConnectionFactory cf = new ConnectionFactory();
    String uri = config.getString("uri");
    // Use uri if set, otherwise support individual connection parameters
    if (uri != null) {
      try {
        cf.setUri(uri);
      } catch (Exception e) {
        throw new IllegalArgumentException("Invalid rabbitmq connection uri " + uri);
      }
    } else {
      String user = config.getString("user");
      if (user != null) {
        cf.setUsername(user);
      }
      String password = config.getString("password");
      if (password != null) {
        cf.setPassword(password);
      }
      String host = config.getString("host");
      if (host != null) {
        cf.setHost(host);
      }
      Integer port = config.getInteger("port");
      if (port != null) {
        cf.setPort(port);
      }
    }

    // Connection timeout
    Integer connectionTimeout = config.getInteger("connectionTimeout");
    if (connectionTimeout != null) {
      cf.setConnectionTimeout(connectionTimeout);
    }
    //TODO: Support other configurations

    return cf.newConnection();
  }

  private static interface ChannelHandler<T> {
    void handle(Channel channel) throws Exception;
  }
}