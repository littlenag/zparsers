package zio.stream.driver

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
 */

sealed trait ProcessingResult[-IR, -IE, +OR, +OE] extends Serializable with Product
final case class Completed[+OR](value: OR) extends ProcessingResult[Any, Any, OR, Nothing]
final case class Failed(msg: String) extends ProcessingResult[Any, Any, Nothing, Nothing]
final case class Yield[IR, IE, OR, OE](toEmit: OE, processor: ReadyProcessor[IR, IE, OR, OE]) extends ProcessingResult[IR, IE, OR, OE]
final case class Next[IR, IE, OR, OE](processor: AwaitingProcessor[IR, IE, OR, OE]) extends ProcessingResult[IR, IE, OR, OE]
final case class Buffer[IR, IE, OR, OE](duration: Duration, processor: SleepingProcessor[IR, IE, OR, OE]) extends ProcessingResult[IR, IE, OR, OE]


// can adapt parsers and coroutines to Processors
// Error channel?
trait Processor[-IR, -IE, +OR, +OE] extends Serializable {
  self =>

  // suspending functions:
  // yield(value)     -> resume with unit
  // next()           -> resume with event
  // buffer(duration) -> resume with list[event]

  // better to have sleep() that doesn't return values and next with a timeout? where 0 means return a queued value?
  // maybe hasWaiting?


  // map function? can map a parser, how about a coroutine?

  //def map[R](f: OR => R): Processor[IR, IE, R, OE]

  //def comap[R](f: R => IR): Processor[R, IE, OR, OE]
}

sealed trait Halted
sealed trait Running

// Processor that has completed with a value, processes no stream elements
case class CompletedProcessor[-IR, -IE, +OR, +OE](value: OR) extends Processor[IR,IE,OR,OE] with Halted {

}

// Processor that has failed with some message, processes no stream elements
case class FailedProcessor[-IR, -IE, +OR, +OE](msg: String) extends Processor[IR,IE,OR,OE] with Halted {
}

// Processor is ready to run
trait ReadyProcessor[-IR, -IE, +OR, +OE] extends Processor[IR,IE,OR,OE] with Running { self =>
  def resume(): ProcessingResult[IR, IE, OR, OE]
}

// Processor is awaiting the next event in the stream
trait AwaitingProcessor[-IR, -IE, +OR, +OE] extends Processor[IR,IE,OR,OE] with Running { self =>
  def process(event: IE): ProcessingResult[IR, IE, OR, OE]
}

// Processor is awaiting a timeout, letting events accrue
trait SleepingProcessor[-IR, -IE, +OR, +OE] extends Processor[IR, IE, OR, OE] with Running { self =>
  def awake(events: List[IE]): ProcessingResult[IR, IE, OR, OE]
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
 * a window needs to be a description of how to build the trace of events that a process will eventually operate on
 *
 * needs a notion of the events seen so far or operated on so far, and notion of events yet to come
 *
 * creating a new window could be a function of
 *  - current processor state
 *  - current stream state
 *  - window state
 *  - events in acc buffer
 *
 *  these things depend on the window op
 *
 *
 * I = In
 * O = Out
 * E = events
 * R = result
 *
 * @tparam IE
 * @tparam OR
 * @tparam OE
 */
sealed trait Window[IE, OR, OE] extends Serializable { self =>

  // this would NOT be a good place for generic map and filter functions for streams!

  val processor: Processor[_, IE, OR, OE]

  val op: WindowOp

  val next: op.Args => Window[IE, _, OE]


  // error recovery?
  val onError: Option[op.Args => Window[IE, _, OE]] = None

  // Events to be processed before those in the stream
  val tracePrefix: List[IE] = Nil

  def withTracePrefix(trace: List[IE]): Window[IE, OR, OE] = new Window[IE, OR, OE] {
    override val tracePrefix: List[IE] = trace
    override val processor = self.processor
    override val op: self.op.type = self.op
    override val next: self.op.Args => Window[IE, _, OE] = self.next
  }

}

object Window {

  // Describes how a Window is to be interpretted
  sealed trait WindowOp extends Serializable {
    type Args
  }

  // Full, closed Window that accepts no more events and has a process that is already halted (completed or failed)
  case class Closed() extends WindowOp {
    type Args = Nothing
  }

  // Full
  case class ToCompletion[IE, OR]() extends WindowOp {
    // if failure
    //   list of events processed
    // else
    //   parser result
    type Args = Either[(String, List[IE]), OR]
  }

  // Partial - run the window over the next N events
  case class ForCount(count:Int) extends WindowOp {
    // to complete the window
    // current processor
    // can you replace a processor mid-window?
    type Args = Unit
  }

  // Partial - run the window for the next duration
  case class ForDuration(duration: Duration) extends WindowOp {
    // to complete the window
    // current processor
    // can you replace a processor mid-window?
    type Args = Unit
  }




//  def apply[IE, OR, OE](
//                         fresh0: Processor[_, IE, OR, OE],
//                         next0: IE => (List[OE], Option[Window[IE, OR, OE]])
//                       ): Window[IE, OR, OE] =
//    apply(fresh0, fresh0, next0)
//
//  def apply[IE, OR, OE](
//                         fresh0: Processor[_, IE, OR, OE],
//                         current0: Processor[_, IE, OR, OE],
//                         next0: IE => (List[OE], Option[Window[IE, OR, OE]])
//                       ): Window[IE, OR, OE] =
//    new Window[IE, OR, OE] {
//      val freshProcessor = fresh0
//      val currentProcessor = current0
//      val next = next0
//    }
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
