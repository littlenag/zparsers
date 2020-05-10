package zio.stream.parsers

object ParsersPlayground {

  /**
  distinguish bounded (time or length) from unbounded from "instantaneous" operations

  Seq could require two bounded ops

  Union parser "|" would have a bound on the entire collection of ops

  Access an AND parser inside a mustContain window

  MustMatch window

  Matches - means an exact match of the complete buffer

  Contains - means some exists one or more time in the buffer

  Starts with

  Ends with

  sequence matcher?
  event matcher?


  items, elements, messages, events, commands, datagrams

  push (zio streams) vs
  pull (scalaz streams) vs
  dynamic push/pull (akka streams)

   https://softwaremill.com/comparing-akka-stream-scalaz-stream/

  */

  // stream of mouse click events

  // double click
  //   - two click events in a short time

  case class MouseClick(button:Int, time:Long /* nanos */ )


  object MouseEvents extends EventPattern[MouseClick] {

    //

    val defaultSeqTimeout = 100 seconds

    val doubleClickCutoff = 100.ms

    case object DoubleClick extends Event

    // match buffer start - requires evidence of a buffer
    Parser.bufferStart

    // match buffer end - requires evidence of a buffer
    Parser.bufferEnd

    // Parsers provide the patterns to match on
    // applying a parser to a stream should result in

    // parsers transform streams of type A into streams of type B
    // compiling a parser should result in a transducer

    val button1 = filter(_.button == 1)

    // A followed by another A within 50 ms, < 50.ms within, > 50.ms not within 50.ms?
    val doubleClickSeq = (button1 ~ (doubleClickCutoff, button1)).as(DoubleClick)

    // how does the window slide forward? on event? via timer?
    // is there a movement policy? as is this would yield successive DoubleClicks for clicks spaced closely together
    val doubleClickInTime = Parser
      .slidingWindow(doubleClickCutoff)  // SlidingWindowParser extends Parser
      .contains(button1 ~ button1)
      .as(DoubleClick)

    val doubleClickInLengthT1 = Parser   // SizedWindowParser extends Parser
      .slidingWindow(2)
      .matches {
        button1 ~ (doubleClickCutoff, button1)
      }.as(DoubleClick)

    val doubleClickInLengthT2 = Parser
      .slidingWindow(2)
      .^^ { case (e1, e2) if (e1.time + 50.ms > e2.time) && e1.button == 1 && e2.button == 1 => DoubleClick }

    // builder pattern for stateful parsers (react builder inspired), this is very monix-like
    val statefulParser = Parser.stateful(0)
    .onMessage {
      case (events, curState) => (emit(events), curState + events.length)) /* work like Push.emit Push.next */
    }
    .onEnd {
      case curState => (next, 0) /* work like Push.emit Push.next */
    }
    .build

    // SingleClick | DoubleClick | TripleClick then choose TripleClick

  }

  // compile parser to
  //  - zio streams  -> ZTransducer? ZConduit?
  //  - akka streams -> Flow
  //  - fs2          -> Pipe
  //  - monix        -> Observable?



}
