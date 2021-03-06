/*
 * Copyright 2015 Daniel Spiewak
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.stream.parsers

//import Matcher._
import cats._
import cats.implicits._
import zio._
import zio.stream._
import org.scalatest._
import org.scalatest.matchers.must.Matchers._

object CharParsers extends Parsers with StreamMatchers {
  override type EventIn = Char

  //implicit override val showEventIn: Show[EventIn] = catsStdShowForChar

  val letterA: Parser[Char] = 'A'

  val letterB: Parser[Char] = 'B'

  val AorB: Parser[Char] = letterA | letterB

  val AB: Parser[String] = letterA ~ letterB ^^ { (_,_) => "AB" }

  val AA: Parser[Int] = (letterA ~ letterA) ^^ ((_, _) => 1)

  lazy val parens: Parser[Int] = (
    ('(' ~> parens) <~ ')' ^^ (1 +)
      | completed(0)
    )

  lazy val parens0: Parser[Int] = (
    (('(' ~> parens) <~ ')')
      | completed(0)
    )

  def run[E, A](zio: => ZIO[ZEnv, E, A]): A = Runtime.default.unsafeRun(zio)

  def parseToEvents[R](parser: Parser[R])(events: Seq[EventIn]): Seq[ParseResult[R]] = {
    run((ZStream(events:_*) >>> matchCutToEvents(parser)).runCollect)
  }

  def parseSuccess[R](parser: Parser[R])(events: Seq[EventIn]): Chunk[R] = {
    run((ZStream(events:_*) >>> matchCut(parser)).runCollect)
  }
}

object TickParsers extends Parsers {

  case class Tick(v: Int, t: Int)

  override type EventIn = Tick

  implicit val showEventIn: Show[Tick] = Show.fromToString

  // the lack of flatMap means that we can't actually detect three increasing values in an intuitive way
  lazy val increasing: Parser[Int] = (
    pattern[Int] {
      case a => a.v
    } ~
    pattern[Int] {
      case a => a.v
    } ~
    pattern[Int] {
      case a => a.v
    } ^^ ((a,b,c) => if (c > b && b > a) 1 else 0 )
  )
}

class ZioStreamSpecs extends wordspec.AnyWordSpec {

  import CharParsers._

  "character stream parse simple" should {
    "parse a" in {
      parseSuccess(AA)("AA") mustEqual Seq(1)
    }
  }

  "character stream parsing" should {

    "parse single a" in {
      parseToEvents(AA)("AA") mustEqual Seq(ParseIncomplete, ParseSuccess(1), ParseEnded)
    }

    "parse one A expecting two" in {
      parseToEvents(AA)("A") mustEqual Seq(ParseIncomplete, ParseEnded)
    }

    "parse parens" in {
      parseToEvents(parens)("()") mustEqual Seq(ParseIncomplete, ParseSuccess(1), ParseEnded)
      parseToEvents(parens)("(((())))").takeRight(3) mustEqual Seq(ParseIncomplete, ParseSuccess(4), ParseEnded)
    }

    "parse unexpected characters correctly" in {
      parseToEvents(parens0)("(b)") mustEqual Seq(ParseIncomplete, ParseFailure("expected '(', got 'b' and(2) expected ')', got 'b'"), ParseFailure("expected '(', got ')'"), ParseEnded)
    }

    "parse B expecting A" in {
      parseToEvents(letterA)("B") mustEqual Seq(ParseFailure("expected 'A', got 'B'"), ParseEnded)
    }

    "parse single A or B" in {
      parseToEvents(AorB)("A") mustEqual Seq(ParseSuccess('A'), ParseEnded)
      parseToEvents(AorB)("B") mustEqual Seq(ParseSuccess('B'), ParseEnded)
    }

    "parse A then B" in {
      parseToEvents(AB)("AB") mustEqual Seq(ParseIncomplete, ParseSuccess("AB"), ParseEnded)
    }
  }
}
