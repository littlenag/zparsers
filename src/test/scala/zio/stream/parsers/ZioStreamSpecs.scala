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

import cats._
import cats.implicits._
import zio._
import zio.stream._
import org.scalatest._
import org.scalatest.matchers.must.Matchers._

class ZioStreamSpecs extends wordspec.AnyWordSpec {
  import Parser._

  case class Tick(v: Int, t: Int)

  implicit val showTick: Show[Tick] = Show.fromToString

  def run[E, A](zio: => ZIO[ZEnv, E, A]): A = Runtime.default.unsafeRun(zio)

  def parseEvents[T:Show,R](parser: Parser[T,R])(events: Seq[T]) = {
    run((ZStream(events:_*) >>> matcher(parser)).runCollect)
  }

  val letterA: Parser[Char, Char] = 'A'

  val letterB: Parser[Char, Char] = 'B'

  val AorB: Parser[Char, Char] = letterA | letterB

  val AB: Parser[Char, String] = letterA ~ letterB ^^ { (_,_) => "AB" }

  lazy val parens: Parser[Char, Int] = (
    ('(' ~> parens) <~ ')' ^^ (1 +)
      | completed(0)
    )

  lazy val parens0: Parser[Char, Int] = (
    (('(' ~> parens) <~ ')')
      | completed(0)
    )

  // the lack of flatMap means that we can't actually detect three increasing values in an intuitive way
  lazy val increasing: Parser[Tick, Int] = (
    Parser.pattern[Tick,Int] {
      case a => a.v
    } ~
      Parser.pattern[Tick,Int] {
        case a => a.v
      } ~
      Parser.pattern[Tick,Int] {
        case a => a.v
      } ^^ ((a,b,c) => if (c > b && b > a) 1 else 0 )
    )

  "parentheses stream parsing" should {

    "parse single a" in {
      val aa = (letterA ~ letterA) ^^ ((_,_) => 1)
      parseEvents(aa)("AA") mustEqual Seq(ParseIncomplete, ParseSuccess(1), ParseEnded)
    }

    "parse parens" in {
      parseEvents(parens)("()") mustEqual Seq(ParseIncomplete, ParseSuccess(1), ParseEnded)
      parseEvents(parens)("(((())))").takeRight(3) mustEqual Seq(ParseIncomplete, ParseSuccess(4), ParseEnded)
    }

    "parse one C expecting two" in {
      val letterCMapped: Parser[Char, Int] = ('C' ~ 'C') ^^ ((_,_) => 2)
      parseEvents(letterCMapped)("C") mustEqual Seq(ParseIncomplete, ParseEnded)
    }

    "parse errors correctly" in {
      parseEvents(parens0)("(b)") mustEqual Seq(ParseIncomplete, ParseFailure("expected '(', got 'b' and(2) expected ')', got 'b'"), ParseFailure("expected '(', got ')'"), ParseEnded)
    }

    "parse B expecting A" in {
      parseEvents(letterA)("B") mustEqual Seq(ParseFailure("expected 'A', got 'B'"), ParseEnded)
    }

    "parse single A or B" in {
      parseEvents(AorB)("A") mustEqual Seq(ParseSuccess('A'), ParseEnded)
      parseEvents(AorB)("B") mustEqual Seq(ParseSuccess('B'), ParseEnded)
    }

    "parse A then B" in {
      parseEvents(AB)("AB") mustEqual Seq(ParseIncomplete, ParseSuccess("AB"), ParseEnded)
    }

  }
}
