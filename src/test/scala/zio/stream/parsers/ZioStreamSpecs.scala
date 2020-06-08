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
import org.specs2.mutable._

object ZioStreamSpecs extends Specification {
  import Parser._

  case class Tick(v: Int, t: Int)

  implicit val showTick: Show[Tick] = Show.fromToString

  def run[E, A](zio: => ZIO[ZEnv, E, A]): A = Runtime.default.unsafeRun(zio)

  "parentheses stream parsing" should {
    lazy val letterA: Parser[Char, Char] = 'A'

    lazy val letterB: Parser[Char, Char] = 'B'

    lazy val letterAorB: Parser[Char, Char] = letterA | letterB

    lazy val AB: Parser[Char, String] = letterA ~ letterB ^^ { (_,_) => "AB" }

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

//    "parse B expecting C" in {
//      val letterCMapped: Parser[Char, Int] = ('C' ~ 'C') ^^ ((_,_) => 2)
//
//      lazy val letterA: Parser[Char, Char] = 'A'
//
//      lazy val letterB: Parser[Char, Char] = 'B'
//
//      lazy val letterAorB: Parser[Char, Char] = letterA | letterB
//
//      val result = ZStream("C": _*) >>> matcher(letterAorB)
//
//      run(result.runCollect) mustEqual Seq(ParseFailure("expected 'C', got 'B'"), ParseEnded)
//    }

    "parse errors correctly" in {
      val result = ZStream("(b)": _*) >>> matcher(parens0)
      run(result.runCollect) mustEqual Seq(ParseIncomplete, ParseFailure("expected '(', got 'b'"), ParseFailure("expected '(', got ')'"), ParseEnded)
    }


//    "parse single space" in {
//      val result = ZStream("()": _*) >>> matcher(parens)
//      run(result.runCollect) mustEqual Seq(ParseIncomplete, ParseSuccess(1), ParseEnded)
//    }

//    "parse single A" in {
//      val result = ZStream("A": _*) >>> matcher(letterA)
//
//      run(result.runCollect) mustEqual Seq(ParseSuccess('A'), ParseEnded)
//    }
//
//    "parse B expecting A" in {
//      val result = ZStream("B": _*) >>> matcher(letterA)
//
//      run(result.runCollect) mustEqual Seq(ParseFailure("expected 'A', got 'B'"), ParseEnded)
//    }
//
//    "parse single A or B" in {
//      val result = ZStream("A": _*) >>> matcher(letterAorB)
//
//      run(result.runCollect) mustEqual Seq(ParseSuccess('A'), ParseEnded)
//    }
//
//    "parse single B or A" in {
//      val result = ZStream("B": _*) >>> matcher(letterAorB)
//
//      run(result.runCollect) mustEqual Seq(ParseSuccess('B'), ParseEnded)
//    }
//
//    "parse single A of AB" in {
//      val result = ZStream("AB": _*) >>> matcher(AB)
//
//      run(result.runCollect) mustEqual Seq(ParseIncomplete, ParseSuccess("AB"), ParseEnded)
//    }
//
//    "parse ticks" in {
//      println("parse ticks ----- start")
//      val result = ZStream(Tick(1,0), Tick(2,1), Tick(4, 2)) >>> matcher(increasing)
//
//      run(result.runCollect) mustEqual Seq(ParseIncomplete, ParseIncomplete, ParseSuccess(1), ParseEnded)
//    }
//
//    "parse ticks 2" in {
//      val result = ZStream(Tick(5,0), Tick(2,1), Tick(4, 2), Tick(6, 2)) >>> matcher(increasing)
//
//      run(result.runCollect) mustEqual Seq(ParseIncomplete, ParseIncomplete, ParseSuccess(0), ParseIncomplete, ParseEnded)
//    }

    //
//    "parse individual single parens" in {
//      val result = ZStream("()()": _*) >>> matcher(parens)
//
//      run(result.runCollect) mustEqual List(ParseIncomplete, ParseSuccess(1), ParseIncomplete, ParseSuccess(1), ParseEnded)
//    }

//    "parse multiple parens" in {
//      val result = Process("()()()()()()()()()()()()()": _*).toSource pipe parse(parens) stripW
//
//      result.runLog.run mustEqual Seq(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0)
//    }
//
//    "parse parens nested at arbitrary depth in sequence" in {
//      val result = Process("((()))(())()((((()))))((()))()(((())))": _*).toSource pipe parse(parens) stripW
//
//      result.runLog.run mustEqual Seq(3, 2, 1, 5, 3, 1, 4, 0)
//    }
  }
}
