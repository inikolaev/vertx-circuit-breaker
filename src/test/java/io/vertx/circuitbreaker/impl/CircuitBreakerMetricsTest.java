package io.vertx.circuitbreaker.impl;

import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.circuitbreaker.CircuitBreakerState;
import io.vertx.circuitbreaker.impl.CircuitBreakerImpl;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.jayway.awaitility.Awaitility.await;
import static io.vertx.circuitbreaker.asserts.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * @author <a href="http://escoffier.me">Clement Escoffier</a>
 */
@RunWith(VertxUnitRunner.class)
public class CircuitBreakerMetricsTest {


  private Vertx vertx;
  private CircuitBreaker breaker;

  @Before
  public void setUp(TestContext tc) {
    vertx = Vertx.vertx();
    vertx.exceptionHandler(tc.exceptionHandler());
  }

  @After
  public void tearDown() {
    if (breaker != null) {
      breaker.close();
    }
    AtomicBoolean completed = new AtomicBoolean();
    vertx.close(ar -> completed.set(ar.succeeded()));
    await().untilAtomic(completed, is(true));
  }


  @Test
  public void testWithSuccessfulCommands(TestContext tc) {
    breaker = CircuitBreaker.create("some-circuit-breaker", vertx);
    Async async = tc.async();


    Future<Void> command1 = breaker.execute(commandThatWorks());
    Future<Void> command2 = breaker.execute(commandThatWorks());
    Future<Void> command3 = breaker.execute(commandThatWorks());

    CompositeFuture.all(command1, command2, command3)
      .setHandler(ar -> {
        assertThat(ar).succeeded();
        assertThat(metrics())
          .contains("name", "some-circuit-breaker")
          .contains("state", CircuitBreakerState.CLOSED.name())
          .contains("failures", 0)
          .contains("totalErrorCount", 0)
          .contains("totalSuccessCount", 3)
          .contains("totalTimeoutCount", 0)
          .contains("totalExceptionCount", 0)
          .contains("totalFailureCount", 0)
          .contains("totalOperationCount", 3)
          .contains("totalSuccessPercentage", 100)
          .contains("totalErrorPercentage", 0);

        async.complete();
      });
  }

  @Test
  public void testWithFailedCommands(TestContext tc) {
    breaker = CircuitBreaker.create("some-circuit-breaker", vertx);
    Async async = tc.async();

    Future<Void> command1 = breaker.execute(commandThatFails());
    Future<Void> command2 = breaker.execute(commandThatWorks());
    Future<Void> command3 = breaker.execute(commandThatWorks());
    Future<Void> command4 = breaker.execute(commandThatFails());

    CompositeFuture.join(command1, command2, command3, command4)
      .setHandler(ar -> {
        assertThat(metrics())
          .contains("name", "some-circuit-breaker")
          .contains("state", CircuitBreakerState.CLOSED.name())
          .contains("totalErrorCount", 2) // Failure + Timeout + Exception
          .contains("totalSuccessCount", 2)
          .contains("totalTimeoutCount", 0)
          .contains("totalExceptionCount", 0)
          .contains("totalFailureCount", 2)
          .contains("totalOperationCount", 4)
          .contains("totalSuccessPercentage", 50)
          .contains("totalErrorPercentage", 50);
        async.complete();
      });
  }

  @Test
  public void testWithCrashingCommands(TestContext tc) {
    breaker = CircuitBreaker.create("some-circuit-breaker", vertx);
    Async async = tc.async();

    Future<Void> command1 = breaker.execute(commandThatFails());
    Future<Void> command2 = breaker.execute(commandThatWorks());
    Future<Void> command3 = breaker.execute(commandThatWorks());
    Future<Void> command4 = breaker.execute(commandThatFails());
    Future<Void> command5 = breaker.execute(commandThatCrashes());

    CompositeFuture.join(command1, command2, command3, command4, command5)
      .setHandler(ar -> {
        assertThat(metrics())
          .contains("name", "some-circuit-breaker")
          .contains("state", CircuitBreakerState.CLOSED.name())
          .contains("totalErrorCount", 3) // Failure + Timeout + Exception
          .contains("totalSuccessCount", 2)
          .contains("totalTimeoutCount", 0)
          .contains("totalExceptionCount", 1)
          .contains("totalFailureCount", 2)
          .contains("totalOperationCount", 5)
          .contains("totalSuccessPercentage", (2.0 / 5 * 100))
          .contains("totalErrorPercentage", (3.0 / 5 * 100));
        async.complete();
      });
  }

  @Test
  public void testWithTimeoutCommands(TestContext tc) {
    breaker = CircuitBreaker.create("some-circuit-breaker", vertx, new CircuitBreakerOptions().setTimeout(100));
    Async async = tc.async();

    Future<Void> command1 = breaker.execute(commandThatFails());
    Future<Void> command2 = breaker.execute(commandThatWorks());
    Future<Void> command3 = breaker.execute(commandThatWorks());
    Future<Void> command4 = breaker.execute(commandThatFails());
    Future<Void> command5 = breaker.execute(commandThatTimeout(100));

    CompositeFuture.join(command1, command2, command3, command4, command5)
      .setHandler(ar -> {
        assertThat(metrics())
          .contains("name", "some-circuit-breaker")
          .contains("state", CircuitBreakerState.CLOSED.name())
          .contains("totalErrorCount", 3) // Failure + Timeout + Exception
          .contains("totalSuccessCount", 2)
          .contains("totalTimeoutCount", 1)
          .contains("totalExceptionCount", 0)
          .contains("totalFailureCount", 2)
          .contains("totalOperationCount", 5)
          .contains("totalSuccessPercentage", (2.0 / 5 * 100))
          .contains("totalErrorPercentage", (3.0 / 5 * 100));

          System.out.println(metrics().encodePrettily());

        async.complete();
      });
  }


  @Test
  public void testLatencyComputation(TestContext tc) {
    breaker = CircuitBreaker.create("some-circuit-breaker", vertx);
    Async async = tc.async();


    int count = 1000;

    List<Future> list = new ArrayList<>();
    for (int i = 0; i < count; i ++) {
      list.add(breaker.execute(commandThatWorks()));
    }

    CompositeFuture.all(list)
      .setHandler(ar -> {
        assertThat(ar).succeeded();
        assertThat(metrics())
          .contains("name", "some-circuit-breaker")
          .contains("state", CircuitBreakerState.CLOSED.name())
          .contains("failures", 0)
          .contains("totalErrorCount", 0)
          .contains("totalSuccessCount", count)
          .contains("totalTimeoutCount", 0)
          .contains("totalExceptionCount", 0)
          .contains("totalFailureCount", 0)
          .contains("totalOperationCount", count)
          .contains("totalSuccessPercentage", 100)
          .contains("totalErrorPercentage", 0);
        assertThat(metrics().getInteger("totalLatencyMean")).isNotZero();
        async.complete();
      });
  }


  private Handler<Future<Void>> commandThatWorks() {
    return (future -> vertx.setTimer(5, l -> future.complete(null)));
  }

  private Handler<Future<Void>> commandThatFails() {
    return (future -> vertx.setTimer(5, l -> future.fail("expected failure")));
  }

  private Handler<Future<Void>> commandThatCrashes() {
    return (future -> {
      throw new RuntimeException("Expected error");
    });
  }

  private Handler<Future<Void>> commandThatTimeout(int timeout) {
    return (future -> vertx.setTimer(timeout + 500, l -> future.complete(null)));
  }

  private Handler<Future<Void>> commandThatTimeoutAndFail(int timeout) {
    return (future -> vertx.setTimer(timeout + 500, l -> future.fail("late failure")));
  }

  private JsonObject metrics() {
    return ((CircuitBreakerImpl) breaker).getMetrics().toJson();
  }

}
