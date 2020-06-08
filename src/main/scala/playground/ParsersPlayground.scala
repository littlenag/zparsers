package playground

object ParsersPlayground {

  /**
   *
   * a matcher is already a driver program for the coroutine that a parser generates
   * parsers generates coroutines that compose, that's the only magic
   *
   * when a matcher evaluates the parser on the next input, could probably just compile
   * the parser to a state monad?
   *
   * parser needs an algebra for communicating with the matcher
   *
   * matchers and handlers as names?
   */

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

  https://softwaremill.com/windowing-data-in-akka-streams/

  After deciding on using event- or processing-time for events, we then have to decide how to
  divide the continuous time into discrete chunks. Here usually there are two options. The
  first is tumbling windows, parametrised by length. Time is divided into non-overlapping parts
  and each data element belongs to a single window. E.g. if the length is 30 minutes, the windows
  would be [12:00, 12:30), [12:30, 13:00), etc. The second option are sliding windows,
  parametrised by length and step. These windows overlap, and each data element can belong to
  multiple windows. For example if the length is 30 minutes, and step 5 minutes, the windows would
  be [12:00, 12:30), [12:05, 12:35), etc.


  ===> problem since you would want to apply patterns to windows

  push (zio streams) vs
  pull (scalaz streams) vs
  dynamic push/pull (akka streams)

   https://softwaremill.com/comparing-akka-stream-scalaz-stream/

    */
  // stream of mouse click events

  // double click
  //   - two click events in a short time

  case class MouseClick(button: Int, time: Long /* nanos */ )

  case object DoubleClick

  /*

  // Clicks in, DoubleClicks out
  object MouseEvents
      extends EventPatternComponent[MouseClick, DoubleClick.type] {

    // From EventPatternComponent
    val in = stream[MouseClick]

    val defaultSeqTimeout = 100 seconds

    val doubleClickCutoff = 100.ms

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
    val doubleClickSeq =
      (button1 ~ (doubleClickCutoff, button1)).as(DoubleClick)

    // how does the window slide forward? on event? via timer?
    // is there a movement policy? as is this would yield successive DoubleClicks for clicks spaced closely together
    val doubleClickInTime = Parser
      .slidingWindow(doubleClickCutoff) // SlidingWindowParser extends Parser
      .contains(button1 ~ button1)
      .as(DoubleClick)

    val doubleClickInLengthT1 = Parser // SizedWindowParser extends Parser
      .slidingWindow(2)
      .matches {
        button1 ~ (doubleClickCutoff, button1)
      }
      .as(DoubleClick)

    val doubleClickInLengthT2 = Parser
      .slidingWindow(2)
      .^^ {
        case (e1, e2)
            if (e1.time + 50.ms > e2.time) && e1.button == 1 && e2.button == 1 =>
          DoubleClick
      }

    // builder pattern for stateful parsers (react builder inspired), this is very monix-like
    val statefulParser = Parser
      .stateful(0)
      .onMessage {
        case (events, curState) => (emit(events), curState + events.length) /* work like Push.emit Push.next */
      }
      .onEnd {
        case curState => (next, 0) /* work like Push.emit Push.next */
      }
      .build

    // spill combines quill's quoted DSL with graph dsl from akka streams
    //   introduce streams graph dsl
    //   should be similar to the akka graph dsl, define a class than can materialize an operator in some API
    //   class should be able to connect inputs and outputs, and introduce new Sources for forking output
    //   Source and Sinks would not necessarily be a concern outside of timers and output ports

    val button1 /*: Parser[MouseClick] */ = quote {
      filter(_.button == 1)
    }

    // A followed by another A within 50 ms, < 50.ms within, > 50.ms not within 50.ms?
    val doubleClickSeq =
      (button1 ~ (doubleClickCutoff, button1)).as(DoubleClick)

    val doubleClicksStream /* : Stream[DoubleClick] */ = quote {
      stream[MouseClick] matching doubleClickSeq
    }

    // An incomplete parse needs to be able to return a list of events to emit
    // again with the co-routine like parsing structure
    // could be made more performant by using special control flow structures that a quoted DSL could parse out
    // co-routine makes passing state easier, since otherwise would have to thread through the parser combinator constructors

    // using a co-routine feels very much like a transducer, or riemann transducer

    // parser as transducer

    val doubleClicksStream2 /* : Stream[DoubleClick] */ = quote {

      // match on the last 10 events (sliding window)
      stream[MouseClick] >>> Matcher.matchWindowed(10.sliding, doubleClickSeq)

      // match on tumbling windows of 10 events
      stream[MouseClick] >>> Matcher.matchWindowed(10.tumbling, doubleClickSeq)

      // discard any events consumed by the parser
      stream[MouseClick] >>> Matcher.matchCut(doubleClickSeq)

      // retry the parser on the next event in the stream, discarding only the first event
      stream[MouseClick] >>> Matcher.matchEvery(doubleClickSeq)

      // Matcher: similar to state monad

      // state is here to record information about emitted or dropped events
      // parser => state, eventBuffer, streamContinuation => state, eventBuffer, streamContinuation

      // parser => (state (may contains eventBuffer, will contain current parser state), nextEvent) => (state, List[Event] (to emit downstream))


      // this would be pretty reasonable to implement in zio, ZMatcher, ZParser

      // ZMatcher is a special kind of Transducer
      // knows how to apply a ZParser, which is a special kind of parser combinator
    }

    */


    // Matchers should probably stay as they are, could take Parser defined as coroutines
    // maybe special case Matcher.fromCoroutine(..) in case it makes lots of sense

    /*

    what would a coroutine-based parser look like? pretty simple actually

        val id = coroutine { (x: Int) => x }

        val vowelcounts = coroutine { (s: String) =>
          yieldval(s.count(_ == 'a'))
          yieldval(s.count(_ == 'e'))
          yieldval(s.count(_ == 'i'))
          yieldval(s.count(_ == 'o'))
          yieldval(s.count(_ == 'u'))
        }

        val button1 = filter(_.button == 1)

        // A followed by another A within 50 ms, < 50.ms within, > 50.ms not within 50.ms?
        val doubleClickSeq =
          (button1 ~ (doubleClickCutoff, button1)).as(DoubleClick)

        // A followed by another A within 50 ms
        val doubleClickCR = coroutine { (ctx: StreamContext) =>
          val b1 = next()
          if (b1.button != 1) fail()

          val b2 = nextWithin(50.ms)
          if (b2.button != 1) fail()

          yieldval(DoubleClick)
        }


// http://storm-enroute.com/coroutines/docs/faq/
// http://reactors.io/
       scala coroutines reactors

// https://wiki.tcl-lang.org/page/parsing+with+coroutine
// http://citeseerx.ist.psu.edu/viewdoc/download?doi=10.1.1.74.6048&rep=rep1&type=pdf



    // From EventPatternComponent
    override def out /* : Stream[DoubleClick] */ = quote {
      in.filter(_.button == 1) matching doubleClickSeq
    }

    override def out /* : Stream[DoubleClick] */ = matching doubleClickSeq

    // SingleClick | DoubleClick | TripleClick then choose TripleClick

  }

  val ztransducer: ZTransducer[Any, _, MouseClick, DoubleClick.type] =
    MouseEvents.doubleClicksStream.compile[ZioTransducer]

  // instantiating the graph would be a stream with a single in and single out
  val ztransducer: ZTransducer[Any, _, MouseClick, DoubleClick.type] =
    MouseEvents.compile[ZioTransducer]


     */


  // parser combinators are
  //   Input -> (Result, Input)

  // riemann follows a similar style to react, both forward event to a children param
  // react is more FRP on the Props, riemann is streaming,

  // react implements the state monad transform
  //   state -> (state, result)
  //   Props -> State -> (State, Component)
  // and implicitly passes events to child components

  //https://www.sderosiaux.com/articles/2018/06/15/a-simple-way-to-write-parsers-using-the-state-monad/

  // akka streams only allow one in and one out for Flows
  // Zio Transducer and Conduit are one in and one out
  //

  // there are three notions that need to be encoded
  //   - streams - an infinite stream of data
  //   - windows - a finite subset of a stream
  //   - matcher -

  // stream of windows?

  // applying a stream to a window expr -> stream of windows
  // applying a stream to a match expr -> ?

  // compile parser to
  //  - zio streams  -> ZTransducer? ZConduit?
  //  - akka streams -> Flow
  //  - fs2          -> Pipe
  //  - monix        -> Observable?

  // samza
  // flink
  // heron

  // esper
  // beepbeep3

}
