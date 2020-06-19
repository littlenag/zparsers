package zio.stream.parsers

import cats._
import cats.data._
import cats.syntax.eq._
import cats.syntax.monad._
import cats.syntax.show._
import cats.syntax.either._

object Syntax {

  type \/[A,B] = Either[A,B]

  def -\/[A, B](a: A): Either[A, B] = Left(a)

  def \/-[A, B](b: B): Either[A, B] = Right(b)
  
}

import Syntax._

trait Parsers {
  // Type of events are we are parsing on
  type EventIn

  // Types of events we'll emit on partial matches
  type EventOut

  /**
   * Applicative (not monadic!) parser interface defined by two functions (simplified types):
   *
   * - `complete: Error \/ Completed`
   * - `derive: State[Cache, Parser]`
   *
   * The `derive` function is only defined on parsers of the subtype, `Incomplete`.  The `complete`
   * function is defined on all parsers, where the following axioms hold:
   *
   * - `complete Completed = \/-(Completed)`
   * - `complete Error = -\/(Error)`
   * - `complete Incomplete = ???`
   *
   * Which is to say that an "Incomplete" parser may be completable, but is also guaranteed to have
   * potential subsequent derivations.  A "Complete" or "Error" parser do not have any further
   * derivations, but their completeness is guaranteed.  An example of an incomplete parser that has
   * subsequent possible derivations but is still completeable is the following:
   *
   * lazy val parens = (
   * '(' ~ parens ~ ')'
   * | completed
   * )
   *
   * The `parens` parser may be completed immediately, since it contains a production for the empty
   * string.  However, it may also be derived, and the only valid derivation for it is over the '('
   * token.  The resulting parser from that derivation *cannot* be completed, since it would require
   * a matching paren in order to represent a valid input.
   *
   * A parser which starts as Incomplete and then becomes either Completed or Error might be something
   * like the following:
   *
   * lazy val foo = literal('a')
   *
   * The `foo` parser is Incomplete and not completable (it contains no production for the empty string).
   * However, it may be derived over the token 'a' to produce a Completed parser (which will actually
   * be of runtime type Completed).  If it is derived over any other token, it will produce an Error
   * parser.
   *
   * Thus, unlike many parser combinators encodings, this one encodes the result algebra directly in
   * the parser itself.  This has several advantages from a usability standpoint.  It does, however,
   * make the encoding somewhat convoluted in a few places from an implementation standpoint.  Hopefully
   * those convolutions do not leak into user space...
   */
  sealed trait Parser[T] {

    /**
     * Attempts to complete the parser, under the assumption that the stream has terminated. If the
     * parser contains a production for the empty string, it will complete and produce its result.
     * Otherwise, if no ε-production exists, an error will be produced.
     *
     * This function allows evaluators to request early termination on a possibly-unbounded incremental
     * parse.  For example, one might define a JSON grammar which parses an unbounded number of JSON
     * values, returning them as a list.  Such a grammar could complete early so long as the prefix
     * string of tokens defines a complete and self-contained JSON value.  This is a desirable property
     * for stream parsers, as it allows the evaluation to be driven (and halted) externally.
     *
     * Any parsers specified in the `seen` set will be treated as already traversed, indicating a cycle
     * in the graph.  Thus, if the traversal recursively reaches these parsers, that node will complete
     * to an error.  For a good time with the whole family, you can invoke `prsr.complete(Set(prsr))`,
     * which will produce an `Error("divergent")` for all non-trivial parsers (namely, parsers that
     * are not `Complete` or `Error` already).
     */
    def complete[R](seen: Set[Parser[_]] = Set()): Either[Error[R], Completed[T]]

    /**
     * Parsers are functors, how 'bout that?  Note the lack of flatMap, though.  No context-sensitive
     * parsers allowed.
     */
    def map[U](f: T => U): Parser[U]
  }

  // yep, indexing on value identity LIKE A BOSS
  type Cache = KMap[Lambda[α => (EventIn, Parser[α])], Lambda[α => () => Parser[α]]]
  //type Cache = KMap[({type λ[α] = (EventIn, Parser[α])})#λ, ({type λ[α] = () => Parser[α]})#λ]

  // creates an empty cache
  def Cache = KMap[({type λ[α] = (EventIn, Parser[α])})#λ, ({type λ[α] = () => Parser[α]})#λ]()

  /**
   * Parser for the empty string, producing a given result.
   */
  def completed[Result](r: Result): Parser[Result] = Completed(r)

  /**
   * Parser that is already in the error state.  Generally speaking, this is probably
   * only useful for internal plumbing.
   */
  def error[Result](msg: String): Parser[Result] = Error(msg)

  /**
   * Parser for a single literal token, producing that token as a result.  Parametricity!
   */
  implicit def literal(token: EventIn)(implicit ev1: Eq[EventIn], ev2: Show[EventIn]): Parser[EventIn] = new Incomplete[EventIn] {

    override val toString: String = s"lit(${token.show})"

    def innerComplete[R](seen: Set[Parser[_]]) = Left(Error(s"unexpected end of stream; expected '${token.show}'"))

    def innerDerive(candidate: EventIn): State[Cache, Parser[EventIn]] = {
      val result: Parser[EventIn] =
        if (candidate === token)
          completed(token)
        else
          error(s"expected '${token.show}', got '${candidate.show}'")

      State pure result
    }
  }

  def pattern[T](pf: PartialFunction[EventIn, T])(implicit ev2: Show[EventIn]): Parser[T] = new Incomplete[T] {

    override val toString: String = s"pattern(...)"

    def innerComplete[R](seen: Set[Parser[_]]) = Left(Error(s"unexpected end of stream"))

    def innerDerive(candidate: EventIn) = {
      val result: Parser[T] = if (pf isDefinedAt candidate)
        completed(pf(candidate))
      else
        error(s"'${candidate.show}' did not match the expected pattern")

      State pure result
    }
  }

  //
  // syntax
  //

  // implicit chaining for literal syntax
  implicit def literalRichParser(token: EventIn)(implicit ev1: Eq[EventIn], ev2: Show[EventIn]): RichParser[EventIn] =
    new RichParser(literal(token))

  // it's somewhat important that these functions be lazy
  implicit class RichParser[Result](left: => Parser[Result]) {

    def as[Result2](f: => Result2): Parser[Result2] = left map (_ => f)

    // alias for map
    def ^^[Result2](f: Result => Result2): Parser[Result2] = left map f

    def ~>[Result2](right: => Parser[Result2]): Parser[Result2] =
      left ~ right ^^ { (_, r) => r }

    def <~[Result2](right: => Parser[Result2]): Parser[Result] =
      left ~ right ^^ { (l, _) => l }

    // alias for andThen
    def ~[Result2](right: => Parser[Result2]) = andThen(right)

    def andThen[Result2](right: => Parser[Result2]): Parser[Result ~ Result2] = {
      new SeqParser(left, right)
    }

    // alias for orElse
    def |(right: => Parser[Result]) = {
      orElse(right)
    }

    def orElse(right: => Parser[Result]): Parser[Result] =
      new UnionParser(left, right)
  }

  implicit class Caret2[A, B](self: Parser[A ~ B]) {

    def ^^[Z](f: (A, B) => Z): Parser[Z] = self map {
      case a ~ b => f(a, b)
    }
  }

  implicit class Caret3L[A, B, C](self: Parser[(A ~ B) ~ C]) {

    def ^^[Z](f: (A, B, C) => Z): Parser[Z] = self map {
      case (a ~ b) ~ c => f(a, b, c)
    }
  }

  implicit class Caret3R[A, B, C](self: Parser[A ~ (B ~ C)]) {

    def ^^[Z](f: (A, B, C) => Z): Parser[Z] = self map {
      case a ~ (b ~ c) => f(a, b, c)
    }
  }

  implicit class Caret4LL[A, B, C, D](self: Parser[((A ~ B) ~ C) ~ D]) {

    def ^^[Z](f: (A, B, C, D) => Z): Parser[Z] = self map {
      case ((a ~ b) ~ c) ~ d => f(a, b, c, d)
    }
  }

  implicit class Caret4LR[A, B, C, D](self: Parser[(A ~ (B ~ C)) ~ D]) {

    def ^^[Z](f: (A, B, C, D) => Z): Parser[Z] = self map {
      case (a ~ (b ~ c)) ~ d => f(a, b, c, d)
    }
  }

  implicit class Caret4RL[A, B, C, D](self: Parser[A ~ ((B ~ C) ~ D)]) {

    def ^^[Z](f: (A, B, C, D) => Z): Parser[Z] = self map {
      case a ~ ((b ~ c) ~ d) => f(a, b, c, d)
    }
  }

  implicit class Caret4RR[A, B, C, D](self: Parser[A ~ (B ~ (C ~ D))]) {

    def ^^[Z](f: (A, B, C, D) => Z): Parser[Z] = self map {
      case a ~ (b ~ (c ~ d)) => f(a, b, c, d)
    }
  }

  //
  // algebra
  //

  // note that this is *not* a NEL; we're going to forbid global ambiguity for now
  final case class Completed[T](result: T) extends Parser[T] {
    def complete[R](seen: Set[Parser[_]]) = \/-(this)

    def map[U](f: T => U): Completed[U] = Completed(f(result))
  }

  // yep!  it's a string.  deal with it
  final case class Error[T](msg: String) extends Parser[T] {
    def complete[R](seen: Set[Parser[_]]) = Left(Error(msg))

    def map[U](f: T => U): Error[U] = Error(msg)
  }

  object Error {
    implicit def monoid[T]: Monoid[Error[T]] = new Monoid[Error[T]] {

      def empty = Error("")

      def combine(e1: Error[T], e2: Error[T]): Error[T] =
        Error(s"${e1.msg} and(0) ${e2.msg}")
    }
  }

  // An incomplete parse needs to be able to return a list of events to emit
  // again with the co-routine like parsing structure
  // could be made more performant by using special control flow structures that a quoted DSL could parse out
  // co-routine makes passing state easier, since otherwise would have to thread through the parser combinator constructors
  sealed trait Incomplete[Result] extends Parser[Result] {
    outer =>

    def map[Result2](f: Result => Result2): Parser[Result2] = new Incomplete[Result2] {

      override def innerComplete[R](seen: Set[Parser[_]]) =
        outer.complete[R](seen).bimap(identity, _ map f)

      override def innerDerive(candidate: EventIn): State[Cache, Parser[Result2]] = {
        val x = outer innerDerive candidate
        x map { p => p.map(f) }
      }

      override lazy val toString: String = s"Incomplete.map"
    }

    override def toString: String = "Incomplete"

    final def complete[R](seen: Set[Parser[_]]): Either[Error[R], Completed[Result]] = {
      // as a side note, this comparison being on pointer identity is the reason this algorithm is O(k^n)
      // if we could magically compare parsers on equivalence of the language they generate, the algorithm
      // would be O(n^2), even if I reenabled global ambiguity support.  SO CLOSE!
      if (seen contains this)
        Left(Error("divergent"))
      else
        innerComplete[R](seen + this)
    }

    protected def innerComplete[R](seen: Set[Parser[_]]): Either[Error[R], Completed[Result]]

    /**
     * Progresses the parse over a single token and returns the continuation (as a parser).  Note that
     * the cache carried in the state monad is very important and must be preserved for the duration
     * of an uncompletable parse.  Once a parser resulting from this derivation is completable, that
     * completion may be invoked and the state dropped.  Dropping state in the middle of an incomplete
     * parse will yield unsound results and possibly divergent parse trails!
     *
     * As the parametricity implies, this derivation function does not advance the parse over anything
     * more than a single token, even if that single token taken in context with the state of the
     * parse coming in cannot yield a valid output.  For example, imagine a parser for matching
     * parentheses.  One could advance the parser over a token representing '('.  This could not
     * possibly yield a completable parser, since it is impossible for a correctly formed parentheses
     * grammar to find a match for a newly-opened parenthetical.  However, the derivation function
     * will still return immediately after consuming the '(' token.  The resulting parser can be used
     * to advance over subsequent tokens, but cannot be completed then-and-there (attempting to do
     * so would result in an Error).
     */
    final def derive(t: EventIn): State[Cache, Parser[Result]] = {
      for {
        cache <- State.get[Cache]
        derived <- cache get (t -> this) map { thunk =>
          val t = thunk()
          println(s"--in cache: $t")
          State.pure[Cache, Parser[Result]](t)
        } getOrElse {
          for {
            _ <- State.pure(println(s"--not in cache"))
            derived <- innerDerive(t)
            _ <- State.pure(println(s"--after inner derive: $derived"))

            cache2 <- State.get[Cache]
            _ <- State set (cache2 + ((t, this) -> { () => derived }))
          } yield derived
        }
      } yield derived
    }

    protected def innerDerive(candidate: EventIn): State[Cache, Parser[Result]]
  }

  //
  // typeclasses
  //

  implicit val parserInstance: Applicative[Parser[*]] = new Applicative[Parser[*]] {

    def pure[A](a: A): Parser[A] = completed(a)

    def ap[A, B](f: Parser[A => B])(fa: Parser[A]): Parser[B] =
      fa ~ f ^^ { (a, f) => f(a) }
  }


  class SeqParser[LR, RR](_left: => Parser[LR], _right: => Parser[RR]) extends Incomplete[LR ~ RR] {

    private lazy val left = _left
    private lazy val right = _right

    override lazy val toString: String = s"(${left}) ~ (${right})"

    def innerComplete[R](seen: Set[Parser[_]]): Error[R] \/ Completed[LR ~ RR] = for {
      clr <- left.complete[R](seen)
      crr <- right.complete[R](seen)
    } yield Completed((clr.result, crr.result))

    def innerDerive(t: EventIn): State[Cache, Parser[LR ~ RR]] = {
      println(s"---seq inner derive. l=$left, r=$right")

      (left, right) match {
        // deriving after completing is an error
        case (Completed(_), Completed(_)) | (Completed(_), Error(_)) => State.pure(Error("unexpected end of stream"))

        case (Error(msg), _) => State.pure(Error(msg))
        case (_, Error(msg)) => State.pure(Error(msg))

        case (Completed(lr), right: Incomplete[RR]) => for {
          rp <- right derive t
        } yield rp map {
          (lr, _)
        } // fails fast?

        case (left: Incomplete[LR], right: Incomplete[RR]) => {
          left.complete(Set()).toOption.map {
            case Completed(lr) => {
              for {
                lp <- left derive t
                rp <- right derive t
              } yield {
                //val x = lp ~ right | (rp map { (lr, _) })
                (lp, rp) match {
                  case (Error(msg1), Error(msg2)) => Error[LR ~ RR](s"$msg1 and(2) $msg2")
                  case (Error(_), _) => rp map {
                    (lr, _)
                  }
                  case (_, Error(_)) => lp ~ right
                  case (_, _) => lp ~ right | (rp map {
                    (lr, _)
                  })
                }
              }
            }
          } getOrElse {
            for {
              lp <- left derive t
            } yield {
              //lp ~ right

              lp match {
                case Error(msg) => Error(msg)
                case _ => lp ~ right
              }
            }
          }
        }
      }
    }
  }

  class UnionParser[Result](_left: => Parser[Result], _right: => Parser[Result]) extends Incomplete[Result] {

    private lazy val left = _left
    private lazy val right = _right

    override lazy val toString: String = s"(${left}) | (${right})"

    def innerComplete[R](seen: Set[Parser[_]]): Either[Error[R], Completed[Result]] = {
      (left.complete[R](seen), right.complete[R](seen)) match {
        case (Right(Completed(_)), Right(Completed(_))) => Either.left(Error("global ambiguity detected"))
        case (lr@Right(Completed(_)), Left(Error(_))) => lr
        case (Left(Error(_)), rr@Right(Completed(_))) => rr
        case (Left(Error(msg)), Left(Error(msg2))) => {
          if (msg == msg2)
            Left(Error(msg))
          else
            Left(Error(s"$msg and(1) $msg2"))
        }
      }
    }

    def innerDerive(t: EventIn): State[Cache, Parser[Result]] = (left, right) match {
      case (Error(leftMsg), Error(rightMsg)) => State.pure(Error(s"$leftMsg -OR- $rightMsg"))

      case (Error(_), Completed(_)) => State.pure(Error("unexpected end of stream"))
      case (Completed(_), Error(_)) => State.pure(Error("unexpected end of stream"))
      case (Completed(_), Completed(_)) => State.pure(Error("unexpected end of stream"))

      case (Error(_) | Completed(_), right: Incomplete[Result]) => right derive t
      case (left: Incomplete[Result], Error(_) | Completed(_)) => left derive t

      case (left: Incomplete[Result], right: Incomplete[Result]) => State { cache =>
        //lazy val xx = left derive t run cache2

        lazy val (cache3, lp) = left derive t run cache2 value
        lazy val (cache4, rp) = right derive t run cache3 value

        lazy val back: Parser[Result] = lp | rp
        lazy val cache2: Cache = cache + ((t, this) -> { () => back })

        // Short circuit if both sides fail
        (lp, rp) match {
          case (Error(l), Error(r)) => (cache4, Error(s"$l -AND- $r"))
          case (_, _) => (cache4, lp | rp)
        }

      }
    }
  }

}