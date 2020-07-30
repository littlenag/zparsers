package zio.stream.driver

import zio._
import zio.stream._
import zio.stream.ZStream._

class ZStreamDriver {

  def applyWindow[I,O,E](window: Window[I,O,E], stream: ZStream[Any, I, O]): ZStream[Any, E, O] = {
    ZStream {

      // setup state here

      for {
        upStream <- stream.process
        bufferedEvents <- ZRef.makeManaged(Chunk[I]())
        curState <- ZRef.makeManaged(window)
        pull = {
          def go: ZIO[Any, Option[E], Chunk[O]] = curState.get.flatMap { window =>

            window.processor match {

              case p: ReadyProcessor[_,I,O,E] =>
                p.resume() match {
                  case Completed(value) =>
                    Pull.emit(value) *> {
                      window.onHalt(Right(value)) match {
                        case Some(nextWindow) => bufferedEvents.set(Chunk[I]()) *> curState.set(nextWindow)
                        case None => Pull.end
                      }
                    }
                  case Failed(err) =>
                    Pull.fail(err) *> bufferedEvents.get.flatMap { e =>
                      window.onHalt(Left((err,e.toList))) match {
                        case Some(nextWindow) => bufferedEvents.set(Chunk[I]()) *> curState.set(nextWindow)
                        case None => Pull.end
                      }
                    }

                  case Yield(toEmit, rp) => (List(toEmit), Some(Window(rp, this.next)))
                  case Next(ap) => (Nil, Some(Window(ap, this.next)))
                  case Buffer(duration, sp) => (Nil, Some(bounded(duration, freshProcessor, sp, this.next)))
              }

              case p: AwaitingProcessor[_,I,O,E] =>
                p.eval(in) match {
                  case Completed(value, toEmit) => (toEmit, None)
                  case Failed(msg) => (Nil, None)                      // TODO propagate error
                  case Yield(toEmit, rp) => (List(toEmit), Some(Window(rp, this.next)))
                  case Next(ap) => (Nil, Some(Window(ap, this.next)))
                  case Buffer(duration, sp) => (Nil, Some(bounded(duration, freshProcessor, sp, this.next)))
                }

              case p: SleepingProcessor[_, IE, OR, OE] =>
                p.wakeup()
            }

              if (_)
                Pull.end
              else
                upStream
                  .map(
                    _.fold(done.set(true) *> push(None).asSomeError)(Pull.fail(_)),
                    os => push(Some(os)).asSomeError
                  )
                  .flatMap(ps => if (ps.isEmpty) go else IO.succeedNow(ps))
          }

          go
        }
      } yield pull
    }
  }

}
