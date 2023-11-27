/*
 * sbt
 * Copyright 2011 - 2018, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package sbt

import sbt.internal.util.RMap

/**
 * Processes progress events during task execution. All methods are called from the same thread
 * except `started` and `finished`, which is called from the executing task's thread. All methods
 * should return quickly to avoid task execution overhead.
 */
trait ExecuteProgress {
  def initial(): Unit

  /**
   * Notifies that a `task` has been registered in the system for execution. The dependencies of
   * `task` are `allDeps` and the subset of those dependencies that have not completed are
   * `pendingDeps`.
   */
  def afterRegistered(
      task: Task[?],
      allDeps: Iterable[Task[?]],
      pendingDeps: Iterable[Task[?]]
  ): Unit

  /**
   * Notifies that all of the dependencies of `task` have completed and `task` is therefore ready to
   * run. The task has not been scheduled on a thread yet.
   */
  def afterReady(task: Task[?]): Unit

  /**
   * Notifies that the work for `task` is starting after this call returns. This is called from the
   * thread the task executes on, unlike most other methods in this callback. It is called
   * immediately before the task's work starts with minimal intervening executor overhead.
   */
  def beforeWork(task: Task[?]): Unit

  /**
   * Notifies that the work for `task` work has finished. The task may have computed the next task
   * to run, in which case `result` contains that next task wrapped in Left. If the task produced a
   * value or terminated abnormally, `result` provides that outcome wrapped in Right. The ultimate
   * result of a task is provided to the `completed` method. This is called from the thread the task
   * executes on, unlike most other methods in this callback. It is immediately called after the
   * task's work is complete with minimal intervening executor overhead.
   */
  def afterWork[A](task: Task[A], result: Either[Task[A], Result[A]]): Unit

  /**
   * Notifies that `task` has completed. The task's work is done with a final `result`. Any tasks
   * called by `task` have completed.
   */
  def afterCompleted[A](task: Task[A], result: Result[A]): Unit

  /** All tasks have completed with the final `results` provided. */
  def afterAllCompleted(results: RMap[Task, Result]): Unit

  /** Notifies that either all tasks have finished or cancelled. */
  def stop(): Unit
}

/**
 * This module is experimental and subject to binary and source incompatible changes at any time.
 */
object ExecuteProgress {
  def empty: ExecuteProgress = new ExecuteProgress {
    override def initial(): Unit = ()
    override def afterRegistered(
        task: Task[?],
        allDeps: Iterable[Task[?]],
        pendingDeps: Iterable[Task[?]]
    ): Unit =
      ()
    override def afterReady(task: Task[?]): Unit = ()
    override def beforeWork(task: Task[?]): Unit = ()
    override def afterWork[A](task: Task[A], result: Either[Task[A], Result[A]]): Unit = ()
    override def afterCompleted[A](task: Task[A], result: Result[A]): Unit = ()
    override def afterAllCompleted(results: RMap[Task, Result]): Unit = ()
    override def stop(): Unit = ()
  }

  def aggregate(reporters: Seq[ExecuteProgress]) = new ExecuteProgress {
    override def initial(): Unit = {
      reporters foreach { _.initial() }
    }
    override def afterRegistered(
        task: Task[?],
        allDeps: Iterable[Task[?]],
        pendingDeps: Iterable[Task[?]]
    ): Unit = {
      reporters foreach { _.afterRegistered(task, allDeps, pendingDeps) }
    }
    override def afterReady(task: Task[?]): Unit = {
      reporters foreach { _.afterReady(task) }
    }
    override def beforeWork(task: Task[?]): Unit = {
      reporters foreach { _.beforeWork(task) }
    }
    override def afterWork[A](task: Task[A], result: Either[Task[A], Result[A]]): Unit = {
      reporters foreach { _.afterWork(task, result) }
    }
    override def afterCompleted[A](task: Task[A], result: Result[A]): Unit = {
      reporters foreach { _.afterCompleted(task, result) }
    }
    override def afterAllCompleted(results: RMap[Task, Result]): Unit = {
      reporters foreach { _.afterAllCompleted(results) }
    }
    override def stop(): Unit = {
      reporters foreach { _.stop() }
    }
  }
}
