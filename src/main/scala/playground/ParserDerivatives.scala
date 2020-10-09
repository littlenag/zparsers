package playground

import cats._
import cats.implicits._
import zio.stream.parsers.ParsersFor
import scala.util.matching.Regex

object ParserDerivatives {

  sealed trait ExprToken

  object ExprToken {
    final case class Num(n: Int) extends ExprToken

    case object Plus extends ExprToken
    case object Minus extends ExprToken
    case object Times extends ExprToken
    case object Div extends ExprToken

    case object LParen extends ExprToken
    case object RParen extends ExprToken
  }


  object Calculator extends ParsersFor[ExprToken] {

      implicit def exprTokenEq[T <: ExprToken]: Eq[T] = Eq.fromUniversalEquals
      implicit def exprTokenShow[T <: ExprToken]: Show[T] = Show.fromToString

      import ExprToken._

      val rules: Map[Regex, List[String] => ExprToken] = Map(
        """\s*(\d+)""".r -> { case ns :: Nil => Num(ns.toInt) },

        """\s*\+""".r -> { _ => Plus },
        """\s*-""".r -> { _ => Minus },
        """\s*\*""".r -> { _ => Times },
        """\s*/""".r -> { _ => Div },

        """\s*\(""".r -> { _ => LParen },
        """\s*\)""".r -> { _ => RParen })

      def exprTokenize(str: String): Seq[ExprToken] =
        regexTokenize(str, rules) collect { case Right(et) => et }

      // %%

      lazy val expr: Parser[Int] = (
        expr ~ Plus ~ term  ^^ { (e1, _, e2) => e1 + e2 }
          | expr ~ Minus ~ term ^^ { (e1, _, e2) => e1 - e2 }
          | term
        )

      lazy val term: Parser[Int] = (
        term ~ Times ~ value ^^ { (e1, _, e2) => e1 * e2 }
          | term ~ Div ~ value   ^^ { (e1, _, e2) => e1 / e2 }
          | value
        )

      // type inference and invariance sort of failed me here...
      lazy val value: Parser[Int] = (
        (LParen: Parser[ExprToken]) ~> expr <~ RParen
          | (pattern { case Num(n) => n })
        )
  }
}
