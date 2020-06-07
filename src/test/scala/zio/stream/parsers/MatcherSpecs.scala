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

import org.specs2.matcher.Matcher
import org.specs2.mutable._

import cats._
import cats.data._
//import cats.syntax.eq._
//import cats.syntax.show._
//import cats.syntax.either._
import cats.implicits._

//import zio._
//import zio.stream._

//import cats.std.anyVal._

import scala.collection.SeqLike
import scala.collection.generic.CanBuildFrom
import scala.util.matching.Regex

object MatcherSpecs extends Specification {
  import Parser._

  //"ZIO mapped parsers" should {

    val litA: Parser[String, String] = "a"
    val mapped0: Parser[String, Int] = litA ^^ (_ => 0)
    val mapped1: Parser[String, Int] = mapped0 ^^ (1 +)

    litA.complete()

    //"parse fail even if mapped" in {
      //mapped must parseComplete("()").as(1)
    //}

  //}

  "ZIO terminal parsers" should {
    lazy val parens: Parser[Char, Int] = (
      '(' ~> parens <~ ')' ^^ (1 +)
      | completed(0)
    )

    "parse single paren set spaces prepended" in {
      parens must parseComplete("()").as(1)
    }

    "ZIO parse the parens" in {
      val epsilon: Parser[Char, Unit] = Parser.completed(())
      epsilon must parseComplete("").as(())
    }


    "ZIO parse the empty string" in {
      val epsilon: Parser[Char, Unit] = Parser.completed(())
      epsilon must parseComplete("").as(())
    }

    "ZIO parse a single token" in {
      val a: Parser[Char, Char] = 'a'
      a must parseComplete("a").as('a')
    }

    "ZIO produce an error when so defined" in {
      val e: Parser[Char, Unit] = Parser.error("oogly boogly")
      e must parseError("fubar").as("oogly boogly")
    }
  }

//  "parentheses matching" should {
//    lazy val grammar: Parser[Char, Int] = (
//        '(' ~> grammar <~ ')' ^^ (1 +)
//      | Parser.completed(0)
//    )
//
//    "parse the empty string" in {
//      grammar must parseComplete("").as(0)
//    }
//
//    "parse a single set of parentheses" in {
//      grammar must parseComplete("()").as(1)
//    }
//
//    "parse four nested sets of parentheses" in {
//      grammar must parseComplete("(((())))").as(4)
//    }
//
//    "fail to parse a single mismatched paren" in {
//      grammar must parseError("(").as("unexpected end of stream; expected )")
//    }
//
//    "fail to parse three mismatched parens with one match" in {
//      grammar must parseError("(((()").as("unexpected end of stream; expected )")
//    }
//
//    "fail to parse a mismatched closing paren" in {
//      grammar must parseError(")").as("expected (, got )")
//    }
//  }

//  "an expression evaluator" should {
//    sealed trait ExprToken
//
//    object ExprToken {
//      final case class Num(n: Int) extends ExprToken
//
//      case object Plus extends ExprToken
//      case object Minus extends ExprToken
//      case object Times extends ExprToken
//      case object Div extends ExprToken
//
//      case object LParen extends ExprToken
//      case object RParen extends ExprToken
//    }
//
//    implicit def exprTokenEq[T <: ExprToken]: Equal[T] = Equal.equalA      // because I'm lazy
//    implicit def exprTokenShow[T <: ExprToken]: Show[T] = Show.showA       // ditto!
//
//    import ExprToken._
//
//    val rules: Map[Regex, List[String] => ExprToken] = Map(
//      """\s*(\d+)""".r -> { case ns :: Nil => Num(ns.toInt) },
//
//      """\s*\+""".r -> { _ => Plus },
//      """\s*-""".r -> { _ => Minus },
//      """\s*\*""".r -> { _ => Times },
//      """\s*/""".r -> { _ => Div },
//
//      """\s*\(""".r -> { _ => LParen },
//      """\s*\)""".r -> { _ => RParen })
//
//    def exprTokenize(str: String): Seq[ExprToken] =
//      regexTokenize(str, rules) collect { case \/-(et) => et }
//
//    // %%
//
//    lazy val expr: Parser[ExprToken, Int] = (
//        expr ~ Plus ~ term  ^^ { (e1, _, e2) => e1 + e2 }
//      | expr ~ Minus ~ term ^^ { (e1, _, e2) => e1 - e2 }
//      | term
//    )
//
//    lazy val term: Parser[ExprToken, Int] = (
//        term ~ Times ~ value ^^ { (e1, _, e2) => e1 * e2 }
//      | term ~ Div ~ value   ^^ { (e1, _, e2) => e1 / e2 }
//      | value
//    )
//
//    // type inference and invariance sort of failed me here...
//    lazy val value: Parser[ExprToken, Int] = (
//        (LParen: Parser[ExprToken, ExprToken]) ~> expr <~ RParen
//      | (Parser pattern { case Num(n) => n })
//    )
//
//    // %%
//
//    "tokenize a number" in {
//      exprTokenize("42") mustEqual Seq(Num(42))
//    }
//
//    "parse a number" in {
//      expr must parseComplete(exprTokenize("42")).as(42)
//      expr must parseComplete(exprTokenize("12")).as(12)
//    }
//
//    "parse a simple addition expression" in {
//      expr must parseComplete(exprTokenize("1 + 2")).as(3)
//    }
//
//    "parse a complex composition of all four operators" in {
//      expr must parseComplete(exprTokenize("228 * 4 + 12")).as(924)
//      expr must parseComplete(exprTokenize("123 + 228 * 4 + 12")).as(1047)
//      expr must parseComplete(exprTokenize("123 - 2 + 228 * 4 + 12")).as(1045)
//      expr must parseComplete(exprTokenize("123 - 2 + 228 * 4 + 12 / 4 + 79")).as(1115)
//      expr must parseComplete(exprTokenize("123 - 2 + 228 * 4 + 12 / 4 + 79 * 5")).as(1431)
//    }
//
//    // TODO more expr tests
//  }

  // TODO maybe move this to a Util object?  seems useful
  def parse[T, R](parser: Parser[T, R])(str: Seq[T]): Error[T, R] Either Completed[T, R] = {
    def inner(str: Seq[T])(parser: Parser[T, R]): State[Parser.Cache[T], Error[T, R] Either Completed[T, R]] = {
      if (str.isEmpty) {
        State pure parser.complete()
      } else {
        parser match {
          case Completed(_) => State pure Left(Error("unexpected end of stream"))
          case e @ Error(_) => State pure Left(e)

          //case parser: Parser.Incomplete[T, R] => parser derive str.head flatMap inner(str.tail)
          case parser: Parser.Incomplete[T, R] => parser derive str.head flatMap inner(str.tail)
        }
      }
    }

    (inner(str)(parser) runA Parser.Cache[T]).value
  }

  // TODO this also seems useful...
  def tokenize[Str[_] <: SeqLike[_, _], TokenIn, TokenOut, That <: TraversableOnce[TokenIn Either TokenOut]](str: Str[TokenIn])(f: Str[TokenIn] => (TokenIn Either TokenOut, Str[TokenIn]))(implicit cbf: CanBuildFrom[Str[TokenIn], TokenIn Either TokenOut, That]): That = {
    if (str.isEmpty) {
      cbf().result
    } else {
      val (token, tail) = f(str)

      val builder = cbf()
      builder += token
      builder ++= tokenize(tail)(f)      // TODO it's never worse, tail-recurse!
      builder.result
    }
  }

  // TODO oh look, more useful stuff!
  def regexTokenize[T](str: String, rules: Map[Regex, List[String] => T]): Seq[Either[Char , T]] = {
    def iseqAsCharSeq(seq: IndexedSeq[Char]): CharSequence = new CharSequence {
      def charAt(i: Int) = seq(i)
      def length = seq.length
      def subSequence(start: Int, end: Int) = iseqAsCharSeq(seq.slice(start, end))
      override def toString = seq.mkString
    }

    tokenize(str: IndexedSeq[Char]) { seq =>
      val str = iseqAsCharSeq(seq)

      // find the "first" regex that matches and apply its transform
      val tokenM: Option[(T, IndexedSeq[Char])] = rules collectFirst {
        case (regex, f) if (regex findPrefixMatchOf str).isDefined => {
          val m = (regex findPrefixMatchOf str).get
          (f(m.subgroups), m.after.toString: IndexedSeq[Char])
        }
      }

      tokenM map {
        case (token, tail) => (Right(token), tail)
      } getOrElse ((Left(seq.head), seq.tail))
    }
  }

  //
  // custom matchers
  //

  def parseComplete[T](str: Seq[T]) = new {
    def as[R: Eq](result: R): Matcher[Parser[T, R]] = {
      def body(parser: Parser[T, R]) = {
        parse(parser)(str) match {
          case Right(Completed(r)) => r === result
          case Left(_) => false
        }
      }

      def error(parser: Parser[T, R]) = parse(parser)(str) match {
        case Left(Error(str)) => s"produces error: $str"
        case Right(Completed(r)) => s"produces result $r rather than expected $result"
      }

      (body _,
        Function.const("parses successfully") _,
        error _)
    }
  }

  def parseError[T](str: Seq[T]) = new {
    def as[R](msg: String): Matcher[Parser[T, R]] = {
      def body(parser: Parser[T, R]) = {
        parse(parser)(str) match {
          case Right(Completed(r)) => false
          case Left(Error(msg2)) => msg === msg2
        }
      }

      def error(parser: Parser[T, R]) = parse(parser)(str) match {
        case Left(Error(msg2)) => s"produced error '$msg2' and not '$msg'"
        case Right(_) => "completed and did not error"
      }

      (body _,
        Function.const(s"produces error $msg") _,
        error _)
    }
  }
}
