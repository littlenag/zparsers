package zio.stream.driver

import java.time.Instant

import zio._
import zio.clock.Clock
import zio.stream._
import zio.stream.ZStream._

class ZStreamDriver(clock: Clock) {

  trait EventTimestamp[E] {
    def eventTimestamp(e:E): Instant
  }

  def applyWindow[R,E,I: EventTimestamp,O](window: Window[I,O,E], stream: ZStream[R, E, I]): ZStream[R, E, O] = {
    ZStream {

      // setup state here

      for {
        upStream <- stream.process
        // events that the window _may_ need to create a replacement window in case of error
        bufferedEvents <- ZRef.makeManaged(Chunk[I]())
        eventsToEmit <- ZRef.makeManaged(Chunk[I]())
        windowState <- ZRef.makeManaged(window)
        pull = {

          def handleProcessResult(pr: ProcessingResult[_, I, O, E]): ZIO[Any, Option[E], Chunk[O]] = pr match {
            case Completed(value) =>
              Pull.emit(value) *> {
                window.onHalt(Right(value)) match {
                  case Some(nextWindow) => bufferedEvents.set(Chunk[I]()) *> windowState.set(nextWindow)
                  case None => Pull.end
                }
              }
            case Failed(err) =>
              Pull.fail(err) *> bufferedEvents.get.flatMap { e =>
                window.onHalt(Left((err,e.toList))) match {
                  case Some(nextWindow) => bufferedEvents.set(Chunk[I]()) *> windowState.set(nextWindow)
                  case None => Pull.end
                }
              }

            case Yield(toEmit, rp) =>
              Pull.emit(toEmit) *> windowState.set(window.withProcessor(rp))

            case Next(ap) =>
              windowState.set(window.withProcessor(ap))

            case Buffer(duration, sp) =>
              (Nil, Some(bounded(duration, freshProcessor, sp, this.next)))
          }


          def go: ZIO[Any, Option[E], Chunk[O]] = windowState.get.flatMap { window =>

            // processor maybe halted
            // events may be waiting for processing in the trace prefix

            window.processor match {

              case CompletedProcessor(value) =>
                window.onHalt(Right(value)) match {
                  case Some(nextWindow) =>
                    bufferedEvents.set(Chunk[I]()) *> windowState.set(nextWindow) *> (
                      if ()
                    )
                  case None => Pull.end
                }

              case FailedProcessor(err) =>
                bufferedEvents.get.flatMap { e =>
                  window.onHalt(Left((err,e.toList))) match {
                    case Some(nextWindow) =>
                      bufferedEvents.set(Chunk[I]()) *> windowState.set(nextWindow)
                    case None => Pull.end
                  }
                }

              case p: Running =>
            }

            val x = ZIO.foreach(window.tracePrefix) { event =>

              window.processor match {

                case p: CompletedProcessor[_,I,O,E] =>
                  handleProcessResult(p.resume())

                case p: FailedProcessor[_,I,O,E] =>
                  handleProcessResult(p.err)

                case p: ReadyProcessor[_,I,O,E] =>
                  handleProcessResult(p.resume())

                case p: AwaitingProcessor[_,I,O,E] =>
                  handleProcessResult(p.process(event))

                case p: SleepingProcessor[_, I, O, E] =>
                  p.wakeup()
              }


            }


            // Process the prefix before any new events
            window.tracePrefix match {
              case head :: tail =>

              case Nil =>

                window.processor match {

                  case p: ReadyProcessor[_, I, O, E] =>
                    p.resume() match {
                      case Completed(value) =>
                        Pull.emit(value) *> {
                          window.onHalt(Right(value)) match {
                            case Some(nextWindow) => bufferedEvents.set(Chunk[I]()) *> windowState.set(nextWindow)
                            case None => Pull.end
                          }
                        }
                      case Failed(err) =>
                        Pull.fail(err) *> bufferedEvents.get.flatMap { e =>
                          window.onHalt(Left((err, e.toList))) match {
                            case Some(nextWindow) => bufferedEvents.set(Chunk[I]()) *> windowState.set(nextWindow)
                            case None => Pull.end
                          }
                        }

                      case Yield(toEmit, rp) =>
                        Pull.emit(toEmit) *> windowState.set(window.withProcessor(rp))

                      case Next(ap) =>
                        upStream.flatMap { v =>


                          windowState.set(window.withProcessor(rp))
                        }
                      case Buffer(duration, sp) => (Nil, Some(bounded(duration, freshProcessor, sp, this.next)))
                    }

                  case p: AwaitingProcessor[_, I, O, E] =>
                    p.eval(in) match {
                      case Completed(value, toEmit) => (toEmit, None)
                      case Failed(msg) => (Nil, None) // TODO propagate error
                      case Yield(toEmit, rp) => (List(toEmit), Some(Window(rp, this.next)))
                      case Next(ap) => (Nil, Some(Window(ap, this.next)))
                      case Buffer(duration, sp) => (Nil, Some(bounded(duration, freshProcessor, sp, this.next)))
                    }

                  case p: SleepingProcessor[_, IE, OR, OE] =>
                    p.wakeup()
                }
              }
            }

          // pull from upstream
          // add resulting events to the trace
          // for each event, let window process, accumulate results and emit

          //

          for {
            newEvents <- upStream
            w <- windowState.get
            _ <- windowState.set(w.withTracePrefix(newEvents.toList))
            _ <- eventsToEmit.set(Chunk[I]())
            toEmit <- go
          } yield toEmit
        }
      } yield pull
    }
  }

}
