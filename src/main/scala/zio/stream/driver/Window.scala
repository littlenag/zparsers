package zio.stream.driver

import java.time.Instant

import org.coroutines.Coroutine
import zio.Chunk
import zio.stream.ZTransducer.Push
import zio.stream.parsers.{ParseFromTo, ParsersFor}

import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.duration.Duration

/**
 *
 * match on parser state? events buffered?
 *
 * if one matcher fails, switch to another?
 *
 * three levels of abstraction
 * proc -> window -> driver
 *
 * proc   - processes a single event
 * window - controls what gets processed, can change based on what has happened, controls cycles of matches, evaluates the window till it completes, then asks for the next window
 * driver - impl in a particular stream framework, evals a window
 *
 * Matcher.forever
 * Matcher.orElse
 * Matcher.once
 *
 * window, job is to evaluate a parser
 *  - on completion, select the next window strategy
 *
 *
 * Window.slide
 * Window.tumble
 * Window.lengthBounded
 * Window.timeBounded
 *
 * window will wrap this function:
 *   processor => in result => next event => (events to emit, option[next window])
 *
 *   combinators for output result?
 *
 *  if next window is none, then matches are done
 *
 *
 * defer will route events to emit into the result
 * ---
 *
 * is there someway to implement a receive channel that could execute within a single thread as its scheduler?
 *
 * trait ReceiveChannel[T] {
 *   def receive: Unit <~> T
 * }
 *
 * you'd just need a channel that knew how to execute a coroutine, just expose and wrap
 * the same state the driver would be doing anyway
 *
 * could be useful since then you could join streams!
 *
 * how to use incoming result to create a new window? new parser?
 * how to pipe the output value of a processor to the input value of the next?
 *
 * --
 *
 * events should be instantaneous, blocks of time consume time
 *
 * can adapt parsers and coroutines to Processors
 * Error channel?
 *
 * I = consumed event type
 * O = emitted event type
 */
sealed trait Processor[I, O] extends Serializable { self =>

  // map function? can map a parser, how about a coroutine?

  //def map[R](f: OR => R): Processor[IR, IE, R, OE]

  //def comap[R](f: R => IR): Processor[R, IE, OR, OE]

  // Query current state
  def state(): ProcessorState

  // If Awaiting, to be called once there is a new event
  // If Sleeping, to be called once enough time has elapsed (gets into wall clock vs event clock issues)
  // Error if called from any other state
  def resume(events: List[I]): Unit   // too much garbage if not side-effecting

  // Only to be called when Sleeping, to inform that a wakeup timer has fired.
  // Upon return, called is expected to process the next state().
  // While the timer is waiting, new events can still be pushed into the Processor via resume(). However,
  // if the Processor detects that enough time has elapsed, then the processor will enter
  // a new state. In this case, if a timer was set, it then must be cancelled.
  // if the events do not eclipse the timeout, then the Processor will remain in the same state.
  def wakeup(): Unit

  sealed trait ProcessorState extends Serializable {
    // Events that should be emitted
    def toEmit: List[O]
  }

  // Processor that has completed with a value (which is NOT emitted), processes no stream elements
  final case class Completed(toEmit: List[O]) extends ProcessorState

  // Processor that has failed with some message, processes no stream elements
  final case class Failed(err: Throwable, toEmit: List[O]) extends ProcessorState

  // Processor is awaiting the next event in the stream
  case class Awaiting(toEmit: List[O]) extends ProcessorState

  // Processor is awaiting a timeout, letting events accrue
  case class Sleeping(start:Instant, end:Instant, toEmit: List[O]) extends ProcessorState

}



/**
 * a window is an algebra that has to be run in the context of a driver
 *
 * the driver needs to be the interpreter
 *
 * windows wrap processors
 *
 * windows needs to operate on traces?
 *
 */

// operations to describe
//   running parser over 5 elements,

// apply processor
//   - till finished
//   - for the next n elements
//   - for some duration
//   - andThen combinator
// what to do once finished

// window wraps
//   - fresh processor to use inside the window
//   - the op
//   - fn to generate next window, if any, args depend on op
//
// ops are either partial or full
//   - partial means that processor may not have completed
//   - full means that the processor will have completed
//
// can only map on full windows, fn over the result that would be produced by the current window
// flatMap on full windows
//
//
// does Window.pure take a value produce a full window that processes no elements?
//  - map just flatMaps into this pure window
//
// have a Window.ended to end the stream

import Window._

/**
 *
 * jobs of the Window
 *  - track events seen so far
 *  - how long to run the processor
 *  - what to do once the processor has either halted (completed/failed) or encountered an upstream error
 *
 * a window needs to be a description of how to build the trace of events that a process will eventually operate on
 *
 * needs a notion of the events seen so far or operated on so far, and notion of events yet to come
 *
 * a window knows how to combine processors
 *
 * a window is a special kind of processor that knows how to wrap other processors and keep them running
 *
 * creating a new window could be a function of
 *  - current processor state
 *  - current stream state
 *  - window state
 *  - events in acc buffer
 *
 * these things depend on the window op
 *
 * @tparam I consumed event type
 * @tparam O emitted event type
 */
class Window[I, O](
                    val freshProcessor: () => Processor[I, O],

                    // Events to be processed before those in the stream
                    val tracePrefix: List[I @uncheckedVariance] = List.empty[I],

                    // Called when the processor halts
                    // Left(events that failed to process)
                    // Right(unit)
                    // returns the next window to run, if any
                    val onHalt: Either[List[I], Unit] => Option[Processor[I, O]] = (_:Either[List[I], Unit]) => None,

                    // called when there is an upstream error
                    // mid-stream error recovery?
                    // takes: events processed, if any
                    // return: none -> window offers no recovery, processing stops; some -> processing continues
                    val onError: Option[List[I] => Processor[I, O]] = None

                  ) extends Processor[I,O] {

  // TODO who/what holds on to events, so they can be retried if the processor fails?

  private var processor = freshProcessor()

  // Query current state
  def state(): ProcessorState = {
    val p = processor
    processor.state() match {
      case p.Completed(toEmit) => Completed(toEmit)
      case p.Failed(t,toEmit) => Failed(t,toEmit)
      case p.Awaiting(toEmit) => Awaiting(toEmit)
      case p.Sleeping(s,e,toEmit) => Sleeping(s,e,toEmit)
    }
  }

  // If Awaiting, to be called once there is a new event
  // If Sleeping, to be called once enough time has elapsed (gets into wall clock vs event clock issues)
  // Error if called from any other state
  def resume(events: List[I]): Unit = {
    // too much garbage if not side-effecting

  }

  override def wakeup(): Unit = processor.wakeup()

  // this would NOT be a good place for generic map and filter functions for streams!
  // reconsider this


}


object Window {


//  def apply[I, R, O](op: WindowOp,
//                     processor: () => Processor[I, R, O],
//                     trace: List[I] = List.empty[I],
//                     onHalt: Either[(O, List[I]), R] => Option[Window[I, R, O]] = _ => None
//                       ): Window[I, R, O] = {
//    new Window[I, R, O](op) {
//      override val tracePrefix: List[I] = trace
//      override def freshProcessor() = processor()
//      override val onHalt = onHalt
//    }
//  }


//  def toCompletion[IE, OR, OE](processor0: Processor[IE, OR, OE]): Window[IE, OR, OE] =
//    new Window[IE, OR, OE](ToCompletion()) {
//      val processor = processor0
//      //val op: WindowOp = ToCompletion()
//    }
//
//  def haltedSuccess[IE, OR, OE](value: OR): Window[IE, OR, OE] =
//    new Window[IE, OR, OE](ToCompletion()) {
//      val processor = CompletedProcessor(value)
//      //val op: WindowOp = ToCompletion()
//    }
//
//  def haltedFailure[IE, OR, OE](err: OE): Window[IE, OR, OE] =
//    new Window[IE, OR, OE](ToCompletion()) {
//      val processor = FailedProcessor(err)
//      //val op: WindowOp = ToCompletion()
//    }
//
//  // Describes how a Window is to be interpreted
//  sealed trait WindowOp extends Serializable
//
//  // Full, closed Window that accepts no more events and has a process that is already halted (completed or failed)
//  case class Closed() extends WindowOp
//
//  // Full
//  case class ToCompletion[IE, OR]() extends WindowOp {
//    // if failure
//    //   list of events processed
//    // else
//    //   parser result
//  }
//
//  // Partial - run the window over the next N events
//  case class ForCount(count:Int) extends WindowOp {
//    // to complete the window
//    // current processor
//    // can you replace a processor mid-window? don't like this idea
//
//    // did the count complete?
//    // if not, what events were accepted? or how many were?
//
//  }
//
//  // Partial - run the window for the next duration
//  case class ForDuration(duration: Duration) extends WindowOp {
//    // to complete the window
//    // current processor
//    // can you replace a processor mid-window? don't really like this idea
//
//    // did the window timeout?
//    // if not, what events were accepted? or how many were?
//
//  }


//
//  def bounded[IE, OR, OE](
//                         duration0: Duration,
//                         fresh0: Processor[_, IE, OR, OE],
//                         current0: Processor[_, IE, OR, OE],
//                         next0: IE => (List[OE], Option[Window[IE, OR, OE]])
//                       ): Window[IE, OR, OE] =
//    new BoundedWindow[IE, OR, OE] {
//      val duration = duration0
//      val freshProcessor = fresh0
//      val currentProcessor = current0
//      val next = next0
//    }
//
//  // Apply a Processor once to a stream of events and then finish
//  def once[IE, OR, OE](
//                        fresh0: Processor[_, IE, OR, OE]
//                      ): Window[IE, OR, OE] =
//    new Window[IE, OR, OE] {
//      import fresh0._
//
//      //type State = S
//      val freshProcessor = fresh0
//      val currentProcessor = fresh0
//      val next = { in =>
//        currentProcessor match {
//          case p: ReadyProcessor[_, IE, OR, OE] =>
//            p.resume() match {
//              case Completed(value, toEmit) => (toEmit, None)
//              case Failed(msg) => (Nil, None)                      // TODO propagate error
//              case Yield(toEmit, rp) => (List(toEmit), Some(Window(rp, this.next)))
//              case Next(ap) => (Nil, Some(Window(ap, this.next)))
//              case Buffer(duration, sp) => (Nil, Some(bounded(duration, freshProcessor, sp, this.next)))
//            }
//
//          case p: AwaitingProcessor[_, IE, OR, OE] =>
//            p.eval(in) match {
//              case Completed(value, toEmit) => (toEmit, None)
//              case Failed(msg) => (Nil, None)                      // TODO propagate error
//              case Yield(toEmit, rp) => (List(toEmit), Some(Window(rp, this.next)))
//              case Next(ap) => (Nil, Some(Window(ap, this.next)))
//              case Buffer(duration, sp) => (Nil, Some(bounded(duration, freshProcessor, sp, this.next)))
//            }
//
//          case p: SleepingProcessor[_, IE, OR, OE] =>
//            p.wakeup()
//        }
//
//
//      }
//    }

}


class ParserProcessor[I, O](val parsers: ParseFromTo[I, O]) extends Processor[I,O] {

  private var p: parsers.Parser[O] = parsers.parser()
  private var c: parsers.Cache = parsers.cache()
  private val toEmit = Seq.newBuilder[O]

  // Query current state
  def state(): ProcessorState = {
    p match {
      case i: parsers.Incomplete[O] => Awaiting(Nil)
      case parsers.Error(msg) => Failed(new RuntimeException(msg),Nil)
      case parsers.Completed(result:O) => Completed(List(result))
      //case p.Sleeping(s,e,toEmit) => Sleeping(s,e,toEmit)
    }
  }

  private def handleEvent(event:I): Unit = {

    p match {
      case parsers.Error(_) =>
        // nothing that can be done
        p = parsers.Error("already halted in Error - cannot handle new event")
        c = parsers.cache()

      case parsers.Completed(_) =>
        p = parsers.Error("already halted in Completed - cannot handle new event")
        c = parsers.cache()

      case ip: parsers.Incomplete[O] =>
        val (updatedCache, derived) = ip.derive(event).run(c).value

        (derived.complete[O](), derived) match {
          case (Left(parsers.Error(_)), nextParser:parsers.Incomplete[O]) =>
            //(updatedCache, nextParser, pr += ParseIncomplete )
            p = nextParser
            c = updatedCache

          case (Left(e : parsers.Error[O]), _) =>
            //(parsers.cache(), initialParser, pr += ParseFailure(msg1) )
            p = e
            c = parsers.cache()

          case (Right(r : parsers.Completed[O]), _) =>
            //(parsers.cache(), initialParser, pr += ParseSuccess(r) )
            p = r
            c = parsers.cache()
        }
    }
  }

  // If Awaiting, to be called once there is a new event
  // If Sleeping, to be called once enough time has elapsed (gets into wall clock vs event clock issues)
  // Error if called from any other state
  def resume(events: List[I]): Unit = {
    // too much garbage if not side-effecting
    events.foreach{handleEvent}
  }

  override def wakeup(): Unit = {}

  // this would NOT be a good place for generic map and filter functions for streams!
  // reconsider this


}


// No way to capture the incoming event type (I) in coroutines just yet
//class CoroutineProcessor[I, O](val coroutine: Coroutine._0[O, Unit]) extends Processor[I,O] {
//
//  private var cr: Coroutine.Instance[O, Unit] = coroutine.inst()
//  private val toEmit = Seq.newBuilder[O]
//
//  // Query current state
//  def state(): ProcessorState = {
//
//    // FIXME always expecting a new value, unless failed. add sleeping support
//    if (cr.hasException) {
//      Failed(cr.getException.get, Nil)
//    } else if (cr.hasResult) {
//      Completed(Nil)
//    } else if (cr.expectsResumeValue) {
//      Awaiting(Nil)
//    } else {
//      throw new RuntimeException("Illegal Coroutine State")
//    }
//  }
//
//  private def handleEvent(event:I): Unit = {
//
//    cr match {
//      case parsers.Error(_) =>
//        // nothing that can be done
//        cr = parsers.Error("already halted in Error - cannot handle new event")
//        c = parsers.cache()
//
//      case parsers.Completed(_) =>
//        cr = parsers.Error("already halted in Completed - cannot handle new event")
//        c = parsers.cache()
//
//      case ip: parsers.Incomplete[O] =>
//        val (updatedCache, derived) = ip.derive(event).run(c).value
//
//        (derived.complete[O](), derived) match {
//          case (Left(parsers.Error(_)), nextParser:parsers.Incomplete[O]) =>
//            //(updatedCache, nextParser, pr += ParseIncomplete )
//            cr = nextParser
//            c = updatedCache
//
//          case (Left(e : parsers.Error[O]), _) =>
//            //(parsers.cache(), initialParser, pr += ParseFailure(msg1) )
//            cr = e
//            c = parsers.cache()
//
//          case (Right(r : parsers.Completed[O]), _) =>
//            //(parsers.cache(), initialParser, pr += ParseSuccess(r) )
//            cr = r
//            c = parsers.cache()
//        }
//    }
//  }
//
//  // If Awaiting, to be called once there is a new event
//  // If Sleeping, to be called once enough time has elapsed (gets into wall clock vs event clock issues)
//  // Error if called from any other state
//  def resume(events: List[I]): Unit = {
//    // too much garbage if not side-effecting
//    events.foreach{handleEvent}
//  }
//
//  override def wakeup(): Unit = {}
//
//  // this would NOT be a good place for generic map and filter functions for streams!
//  // reconsider this
//
//
//}
