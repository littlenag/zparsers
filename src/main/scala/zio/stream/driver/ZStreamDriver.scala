package zio.stream.driver

import java.time.Instant
import java.util.concurrent.TimeUnit

import zio._
import zio.clock.Clock
import zio.duration.Duration
import zio.stream._
import zio.stream.ZStream._

class ZStreamDriver(clock: Clock) {

  trait EventTimestamp[E] {
    def eventTimestamp(e:E): Instant
  }

  def throughProcessor[Env,I:EventTimestamp,O,E](stream: ZStream[Env, E, I], processor: Processor[I,O]): ZStream[Env, E, O] = {
    import processor._

    ZStream {

      // setup state here

      /*
       *
       * ref, with boolean of whether or not to call wakeup, that pull checks immediately, prior to pull from upstream
       * ref, handle to fiber that is waiting to ping the wakeup alarm
       * ref, of the previous processor state
       * if a processor transitions away from its current sleeping state to a new state, then cancel the wakeup alarm fiber
       *   - if to a new sleeping state, then start a new fiber with a new timeout
       *
       * how to handle starting in a sleeping state?
       */
      for {
        upStream <- stream.process

        // exists in case we need a side-fiber for timeouts
        wakeupAlarmFiber <- ZRef.makeManaged(Option.empty[Fiber.Runtime[Unit, Unit]])
        doWakeupAlarm <- ZRef.makeManaged(false)

        // FIXME handle processors that start in a sleeping state
        previousProcessorState <- ZRef.makeManaged(processor.state())

        pull = {

          def mkWakup(alarm:Duration): ZIO[Any, Unit, Unit] = {
            val action = for {
              _ <- ZIO.sleep(alarm)
              _ <- doWakeupAlarm.set(true)
            } yield ()

            for {
              fiber <- action.onInterrupt(wakeupAlarmFiber.set(None)).provide(clock).fork
              _ <- wakeupAlarmFiber.set(Option(fiber))
            } yield ()

          }

          def go(events: Chunk[I]): ZIO[Any, Option[E], Chunk[O]] = {
            for {
              // are you resuming into a sleeping processor or a active processor?
              _ <- Task(processor.resume(events.toList)).mapError(_ => None)

              previousState <- previousProcessorState.get

              maybeFiber <- wakeupAlarmFiber.get

              // if the state changed, and we have a wake fiber, then cancel it

              /*
              if there is a wake fiber
                and the resume changes/eliminates the wakeup time,
                  then
                    interrupt the fiber, and clear wakup alarm bool
                    handle the new events
                    if the new state is a sleeping state
                      start a wake fiber
                  else
                    there should be no new events
               else
                  handle the new events
                  if the new state is a sleeping state
                    start a wake fiber
               */
              toEmit <- ( (maybeFiber, previousState, processor.state()) match {

                case (Some(_), Sleeping(_,e1,_), Sleeping(_,e2,_)) if e1 == e2 =>
                  Pull.empty[O]

                case (Some(fiber), _, Sleeping(e1,e2,toEmit)) =>

                  val duration = zio.duration.Duration(e2.toEpochMilli - e1.toEpochMilli, TimeUnit.MILLISECONDS)

                  fiber.interrupt *> doWakeupAlarm.set(false) *> mkWakup(duration) *> Pull.emit[O](Chunk.fromIterable(toEmit))

                case (None, _, Completed(toEmit)) =>
                  // FIXME
                  Pull.emit(Chunk.fromIterable(toEmit)) *> Pull.end

                case (None, _, Failed(err, toEmit)) =>
                  // FIXME
                  Pull.emit(Chunk.fromIterable(toEmit)) *> Pull.end

                case (None, _, Awaiting(toEmit)) =>
                  Pull.emit(Chunk.fromIterable(toEmit))

                case (None, _, Sleeping(e1,e2,toEmit)) =>

                  val duration = zio.duration.Duration(e2.toEpochMilli - e1.toEpochMilli, TimeUnit.MILLISECONDS)

                  doWakeupAlarm.set(false) *> mkWakup(duration) *> Pull.emit[O](Chunk.fromIterable(toEmit))
              } ).mapError(_ => Option.empty[E])

            } yield toEmit

          }

          // pull from upstream
          // for each event, let window process, accumulate results and emit

          for {
            //
            doWakeup <- doWakeupAlarm.get
            toEmitFromWakeup <- if (doWakeup) {
              processor.wakeup()

              // we assume the fiber will clean itself up upon its clean exit OR its interruption
              // so you should never have an alarm set AND a fiber running at the same time

              // and process the next state

              val action = processor.state() match {
                case Completed(toEmit) =>
                  // FIXME seems that events won't be emitted that maybe should be?
                  // need another ref for cleanup maybe?
                  Pull.emit(Chunk.fromIterable(toEmit)) *> Pull.end

                case Failed(err, toEmit) =>
                  // FIXME seems that events won't be emitted that should be
                  Pull.emit(Chunk.fromIterable(toEmit)) *> Pull.fail(null.asInstanceOf[E])

                case Awaiting(toEmit) =>
                  Pull.emit(Chunk.fromIterable(toEmit))

                case Sleeping(start, end, toEmit) =>
                  // how to buffer?
                  Pull.emit(Chunk.fromIterable(toEmit))
              }

              // Clear the flag since it has been handled
              doWakeupAlarm.set(false) *> action
            } else {
              Pull.empty
            }

            //
            newEvents <- upStream
            toEmit <- go(newEvents)
          } yield toEmitFromWakeup ++ toEmit
        }
      } yield pull
    }
  }

}
