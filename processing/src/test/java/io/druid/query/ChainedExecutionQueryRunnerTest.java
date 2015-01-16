/*
 * Druid - a distributed column store.
 * Copyright (C) 2012, 2013, 2014  Metamarkets Group Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package io.druid.query;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListenableFuture;
import com.metamx.common.concurrent.ExecutorServiceConfig;
import com.metamx.common.guava.Sequence;
import com.metamx.common.guava.Sequences;
import com.metamx.common.lifecycle.Lifecycle;
import io.druid.query.aggregation.AggregatorFactory;
import io.druid.query.aggregation.CountAggregatorFactory;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ChainedExecutionQueryRunnerTest
{
  private final Lock neverRelease = new ReentrantLock();

  @Before
  public void setup()
  {
    neverRelease.lock();
  }
  
  @Test(timeout = 60000)
  public void testQueryCancellation() throws Exception
  {
    ExecutorService exec = PrioritizedExecutorService.create(
        new Lifecycle(), new ExecutorServiceConfig()
        {
          @Override
          public String getFormatString()
          {
            return "test";
          }

          @Override
          public int getNumThreads()
          {
            return 2;
          }
        }
    );

    final CountDownLatch queriesStarted = new CountDownLatch(2);
    final CountDownLatch queriesInterrupted = new CountDownLatch(2);
    final CountDownLatch queryIsRegistered = new CountDownLatch(1);

    Capture<ListenableFuture> capturedFuture = EasyMock.newCapture();
    QueryWatcher watcher = EasyMock.createStrictMock(QueryWatcher.class);
    watcher.registerQuery(
        EasyMock.<Query>anyObject(),
        EasyMock.and(EasyMock.<ListenableFuture>anyObject(), EasyMock.capture(capturedFuture))
    );
    EasyMock.expectLastCall()
            .andAnswer(
                new IAnswer<Void>()
                {
                  @Override
                  public Void answer() throws Throwable
                  {
                    queryIsRegistered.countDown();
                    return null;
                  }
                }
            )
            .once();

    EasyMock.replay(watcher);

    ArrayBlockingQueue<DyingQueryRunner> interrupted = new ArrayBlockingQueue<>(3);
    Set<DyingQueryRunner> runners = Sets.newHashSet(
        new DyingQueryRunner(queriesStarted, queriesInterrupted, interrupted),
        new DyingQueryRunner(queriesStarted, queriesInterrupted, interrupted),
        new DyingQueryRunner(queriesStarted, queriesInterrupted, interrupted)
    );

    ChainedExecutionQueryRunner chainedRunner = new ChainedExecutionQueryRunner<>(
        exec,
        Ordering.<Integer>natural(),
        watcher,
        Lists.<QueryRunner<Integer>>newArrayList(
         runners
        )
    );
    Map<String, Object> context = ImmutableMap.<String, Object>of();
    final Sequence seq = chainedRunner.run(
        Druids.newTimeseriesQueryBuilder()
              .dataSource("test")
              .intervals("2014/2015")
              .aggregators(Lists.<AggregatorFactory>newArrayList(new CountAggregatorFactory("count")))
              .build(),
        context
    );

    Future resultFuture = Executors.newFixedThreadPool(1).submit(
        new Runnable()
        {
          @Override
          public void run()
          {
            Sequences.toList(seq, Lists.newArrayList());
          }
        }
    );

    // wait for query to register and start
    queryIsRegistered.await();
    queriesStarted.await();

    // cancel the query
    Assert.assertTrue(capturedFuture.hasCaptured());
    ListenableFuture future = capturedFuture.getValue();
    future.cancel(true);

    QueryInterruptedException cause = null;
    try {
      resultFuture.get();
    }
    catch (ExecutionException e) {
      Assert.assertTrue(e.getCause() instanceof QueryInterruptedException);
      cause = (QueryInterruptedException) e.getCause();
    }
    queriesInterrupted.await();
    Assert.assertNotNull(cause);
    Assert.assertTrue(future.isCancelled());

    DyingQueryRunner interrupted1 = interrupted.poll();
    synchronized (interrupted1) {
      Assert.assertTrue("runner 1 started", interrupted1.hasStarted);
      Assert.assertTrue("runner 1 interrupted", interrupted1.interrupted);
    }
    DyingQueryRunner interrupted2 = interrupted.poll();
    synchronized (interrupted2) {
      Assert.assertTrue("runner 2 started", interrupted2.hasStarted);
      Assert.assertTrue("runner 2 interrupted", interrupted2.interrupted);
    }
    runners.remove(interrupted1);
    runners.remove(interrupted2);
    DyingQueryRunner remainingRunner = runners.iterator().next();
    synchronized (remainingRunner) {
      Assert.assertTrue("runner 3 should be interrupted or not have started",
                        !remainingRunner.hasStarted || remainingRunner.interrupted);
    }
    Assert.assertFalse("runner 1 not completed", interrupted1.hasCompleted);
    Assert.assertFalse("runner 2 not completed", interrupted2.hasCompleted);
    Assert.assertFalse("runner 3 not completed", remainingRunner.hasCompleted);

    EasyMock.verify(watcher);
  }

  @Test(timeout = 60000)
  public void testQueryTimeout() throws Exception
  {
    ExecutorService exec = PrioritizedExecutorService.create(
        new Lifecycle(), new ExecutorServiceConfig()
        {
          @Override
          public String getFormatString()
          {
            return "test";
          }

          @Override
          public int getNumThreads()
          {
            return 2;
          }
        }
    );

    final CountDownLatch queriesStarted = new CountDownLatch(2);
    final CountDownLatch queriesInterrupted = new CountDownLatch(2);
    final CountDownLatch queryIsRegistered = new CountDownLatch(1);

    Capture<ListenableFuture> capturedFuture = new Capture<>();
    QueryWatcher watcher = EasyMock.createStrictMock(QueryWatcher.class);
    watcher.registerQuery(
        EasyMock.<Query>anyObject(),
        EasyMock.and(EasyMock.<ListenableFuture>anyObject(), EasyMock.capture(capturedFuture))
    );
    EasyMock.expectLastCall()
            .andAnswer(
                new IAnswer<Void>()
                {
                  @Override
                  public Void answer() throws Throwable
                  {
                    queryIsRegistered.countDown();
                    return null;
                  }
                }
            )
            .once();

    EasyMock.replay(watcher);


    ArrayBlockingQueue<DyingQueryRunner> interrupted = new ArrayBlockingQueue<>(3);
    Set<DyingQueryRunner> runners = Sets.newHashSet(
        new DyingQueryRunner(queriesStarted, queriesInterrupted, interrupted),
        new DyingQueryRunner(queriesStarted, queriesInterrupted, interrupted),
        new DyingQueryRunner(queriesStarted, queriesInterrupted, interrupted)
    );

    ChainedExecutionQueryRunner chainedRunner = new ChainedExecutionQueryRunner<>(
        exec,
        Ordering.<Integer>natural(),
        watcher,
        Lists.<QueryRunner<Integer>>newArrayList(
            runners
        )
    );
    HashMap<String, Object> context = new HashMap<String, Object>();
    final Sequence seq = chainedRunner.run(
        Druids.newTimeseriesQueryBuilder()
              .dataSource("test")
              .intervals("2014/2015")
              .aggregators(Lists.<AggregatorFactory>newArrayList(new CountAggregatorFactory("count")))
              .context(ImmutableMap.<String, Object>of("timeout", 100, "queryId", "test"))
              .build(),
        context
    );

    Future resultFuture = Executors.newFixedThreadPool(1).submit(
        new Runnable()
        {
          @Override
          public void run()
          {
            Sequences.toList(seq, Lists.newArrayList());
          }
        }
    );

    // wait for query to register and start
    queryIsRegistered.await();
    queriesStarted.await();

    Assert.assertTrue(capturedFuture.hasCaptured());
    ListenableFuture future = capturedFuture.getValue();

    // wait for query to time out
    QueryInterruptedException cause = null;
    try {
      resultFuture.get();
    }
    catch (ExecutionException e) {
      Assert.assertTrue(e.getCause() instanceof QueryInterruptedException);
      Assert.assertEquals("Query timeout", e.getCause().getMessage());
      cause = (QueryInterruptedException) e.getCause();
    }
    queriesInterrupted.await();
    Assert.assertNotNull(cause);
    Assert.assertTrue(future.isCancelled());

    DyingQueryRunner interrupted1 = interrupted.poll();
    synchronized (interrupted1) {
      Assert.assertTrue("runner 1 started", interrupted1.hasStarted);
      Assert.assertTrue("runner 1 interrupted", interrupted1.interrupted);
    }
    DyingQueryRunner interrupted2 = interrupted.poll();
    synchronized (interrupted2) {
      Assert.assertTrue("runner 2 started", interrupted2.hasStarted);
      Assert.assertTrue("runner 2 interrupted", interrupted2.interrupted);
    }
    runners.remove(interrupted1);
    runners.remove(interrupted2);
    DyingQueryRunner remainingRunner = runners.iterator().next();
    synchronized (remainingRunner) {
      Assert.assertTrue("runner 3 should be interrupted or not have started",
                        !remainingRunner.hasStarted || remainingRunner.interrupted);
    }
    Assert.assertFalse("runner 1 not completed", interrupted1.hasCompleted);
    Assert.assertFalse("runner 2 not completed", interrupted2.hasCompleted);
    Assert.assertFalse("runner 3 not completed", remainingRunner.hasCompleted);

    EasyMock.verify(watcher);
  }

  private class DyingQueryRunner implements QueryRunner<Integer>
  {
    private final CountDownLatch start;
    private final CountDownLatch stop;
    private final Queue<DyingQueryRunner> interruptedRunners;

    private volatile boolean hasStarted = false;
    private volatile boolean hasCompleted = false;
    private volatile boolean interrupted = false;

    public DyingQueryRunner(CountDownLatch start, CountDownLatch stop, Queue<DyingQueryRunner> interruptedRunners)
    {
      this.start = start;
      this.stop = stop;
      this.interruptedRunners = interruptedRunners;
    }

    @Override
    public Sequence<Integer> run(Query<Integer> query, Map<String, Object> responseContext)
    {
      // do a lot of work
      synchronized (this) {
        try {
          hasStarted = true;
          start.countDown();
          neverRelease.lockInterruptibly();
        }
        catch (InterruptedException e) {
          interrupted = true;
          interruptedRunners.offer(this);
          stop.countDown();
          throw new QueryInterruptedException("I got killed");
        }
      }

      hasCompleted = true;
      stop.countDown();
      return Sequences.simple(Lists.newArrayList(123));
    }
  }
}
