package zio.stream.parsers

import cats._
//import cats.data._
//import cats.syntax.show._
//import cats.syntax.either._
import zio._
import zio.stream._
import zio.stream.ZTransducer.Push

trait StreamMatchers { self: Parsers =>

  sealed trait ParseResult[+Result] extends Product with Serializable
  final case class ParseSuccess[+Result](value: Result) extends ParseResult[Result]
  final case class ParseFailure[+Result](msg: String) extends ParseResult[Result]
  final case object ParseIncomplete extends ParseResult[Nothing]
  final case object ParseEnded extends ParseResult[Nothing]

  //implicit def showEventIn: Show[EventIn]
  //implicit def showEventOut: Show[EventOut]

  /*
      stream[MouseClick] >>> Matcher.matchWindowed(10.sliding, doubleClickSeq)

      // match on tumbling windows of 10 events
      stream[MouseClick] >>> Matcher.matchWindowed(10.tumbling, doubleClickSeq)

      // discard any events consumed by the parser
      stream[MouseClick] >>> Matcher.matchCut(doubleClickSeq)

      // retry the parser on the next event in the stream, discarding only the first event
      stream[MouseClick] >>> Matcher.matchEvery(doubleClickSeq)

      matchTumbling
   */

  def filterSuccesses[Result] = ZTransducer
    .identity[ParseResult[Result]]
    .filter {
      case ParseSuccess(_) => true
      case _ => false
    }.map {
      case ParseSuccess(value) => value
    }

  def matchCut[R](parser: Parser[R]): ZTransducer[Any, Nothing, EventIn, R] = {
    matchCutToEvents(parser) >>> filterSuccesses[R]
  }


  /**
   * Matches as data arrives and emits on every event.  Note that this may not be
   * exactly what you want!  The exact semantics here are to use the parser to
   * consume tokens as long as the parser *requires* more tokens in order to emit
   * a valid output, or until the parser derives an error on all branches.  Thus,
   * as soon as the parser can possibly emit a valid value, it will do so.  At
   * that point, the parser state is flushed and restarted with the next token.
   * This is sort of the moral equivalent of the `findAllMatches` function on
   * Regex, but applied repeatedly to the input stream.
   *
   * I can't quite contrive a scenario where these semantics result in undesirable
   * behavior, but I'm sure they exist.  If nothing else, it seems clear that there
   * are a lot of arbitrary defaults baked into these semantics, and it would be
   * nice to have a bit more control.
   *
   * Sidebar: this function *does* attempt to prevent completely vacuuous parse
   * results.  Providing a parser which accepts the empty string will not result
   * in an infinite stream of successes without consuming anything.
   */
  def matchCutToEvents[R](parser: Parser[R]): ZTransducer[Any, Nothing, EventIn, ParseResult[R]] = {
    ZTransducer {
      //import Parser._

      // TODO handle other initial parser states
      val initialParser = parser.asInstanceOf[Incomplete[R]]

      val cleanState = (Cache, parser, false)   // have NOT flushed current state?
      val flushedState = (Cache, parser, true)  // have YES flushed current state?

      for {
        curState <- ZRef.makeManaged[(Cache, Parser[R], Boolean)](cleanState)
        push = { (input: Option[Chunk[EventIn]]) =>
          input match {
            // None is received when the upstream element has terminated
            case None =>
              curState
                .modify  {
                  case e @ (_, _, true) => Push.next -> flushedState
                  case e @ (_, _@Completed(result), false) => Push.emit(ParseSuccess(result)) -> flushedState
                  case e @ (_, _@Error(msg), false) => Push.emit(ParseFailure(msg)) -> flushedState
                  case e @ (_, _:Incomplete[R], false) => Push.emit(ParseEnded) -> flushedState
                }
                .flatten

            case Some(is) =>

              curState
                .modify {
                  // parser should only ever be in an incomplete state
                  case e @ (cache, parser:Incomplete[R], _) =>

                    val builder = Seq.newBuilder[ParseResult[R]]
                    builder.sizeHint(is.length)
                    val initialState = (cache, parser, builder)

                    // For each event in the chunk, push it to the parser, process the result
                    val (finalCache, finalParser, toEmit) = is.foldLeft(initialState) { case ((cache, parser, pr), event) =>

                      val (newCache, derived) = parser.derive(event).run(cache).value

                      println(s">>input: '$event', parser: $parser ~> parser: $derived")

                      val ret = (derived.complete(), derived) match {
                        case (Left(Error(msg1)), Completed(value)) =>
                          println(s"unexpected completion: $msg1 with value $value")
                          (Cache, initialParser, pr += ParseFailure(msg1) )

                        case (Left(Error(msg1)), _@Error(msg2)) =>
                          println(s"completion error: $msg1")
                          println(s"parser-error: $msg2")
                          (Cache, initialParser, pr += ParseFailure(msg2) )

                        case (Left(Error(msg)), nextParser:Incomplete[R]) =>
                          println(s"incomplete parse. error: $msg")
                          (newCache, nextParser, pr += ParseIncomplete )

                        case (Right(Completed(r)), _) =>
                          (Cache, initialParser, pr += ParseSuccess(r) )
                      }

                      println("<<")
                      ret
                    }

                    Push.emit(Chunk.fromArray(toEmit.result().toArray)) -> (finalCache, finalParser, false)

                  case e @ (_, _, _) =>
                    println("!!!!!!!!!!!!!!!!!!! unexpected !!!!!!!!!!!!!!!!!!!!!!!!")
                    Push.next /*ZIO.dieMessage(s"Parser continued with terminated parser. $e")*/ -> cleanState

                }.flatten
          }
        }
      } yield push
    }
  }

}
