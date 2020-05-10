# event pattern combinators

probably want some way to force stop a match by joining on effect, or listen to a separate queue to close a match
 * example would be alert pattern that should stay open, until an operator acknowledges the alert
 * could also be accomplished by joining on a command stream
 
lots of helpers for matching on event
 * scalatest pattern match macro, force logic into the pattern match
 
some way to match in parallel?
 * multi-match should be enterprise feature
 
compile to state machine
 * enterprise feature 


TODO
 * scalaz -> cats type-classes 
 * scalaz-streams -> zio-streams -> agnostic
 * time window combinators
   - cascading
   - tumbling
   - rolling
   - sliding

NOTES
 * whitespace is a non-issue
 
 
Base combinator logic

matchers act like filters, parsers do not.

operator logic


//
onNext event -> append to buffer

onPull -> 
while(process) {

  var matchFound = false
  val buffer = []
  while (!matchFound) {
    // matches may return
    //   - error, no match
    //   - completed with value
    //   - incomplete (could we co-routine this so that we don't have to repeat parsing?)
    //     - completed with continuation?
    //     - how to model consuming some input, emitting an event, and then resuming on later input

  }

}


# sparse

This library provides combinators for fully general (any CFG!), fully incremental parsing on top of [scalaz-stream](https://github.com/scalaz/scalaz-stream).  An example:

```scala
import scalaz.std.anyVal._

import scalaz.stream._
import scalaz.stream.parsers._

import Parser._

// returns the depth of parentheses nesting
lazy val parens: Parser[Char, Int] = (
	'(' ~> parens <~ ')' ^^ (1 +)
  | completed(0)
)

Process("((()))()(())": _*) pipe parse(parens) stripW		// => Process(3, 1, 2, 0)
```

The parsing algorithm is independent of the algorithm which drives the evaluation of a given parser over a stream (which is to say, the function which converts a `Parser` to a `Process1`).  At present, the only evaluator which is implemented (the `parse` function) is a very greedy prefix evaluator that special-case prevents vacuous epsilon matching.  You should think of this as very similar to the `Regex#findPrefixOf` function, applied repeatedly and incrementally to the incoming stream.  Vacuous epsilon matching is prevented to prevent grammars like the above from producing an infinite stream of empty results (in this case, an infinite stream of `0`).

## Getting Started

Clone the repository and build the SBT project.  At the very least, you should be able to get a REPL and poke around with some things (scalaz and scalaz-stream will be on the classpath).  If this doesn't work, uh… yell at me on twitter, or something.

### SBT

If you feel adventurous (or perhaps, less adventurous), you can use a snapshot release pushed to [Bintray](https://bintray.com).  The following incantation in your `build.sbt` should do the trick:

```sbt
resolvers += "djspiewak's bintray" at "http://dl.bintray.com/djspiewak/maven"

libraryDependencies += "com.codecommit" %% "sparse" % [version]
```

The specific value of `[version]` is variable.  Snapshot releases are pushed with the convention "`master-[git hash]`" (for example: `master-d7b3dca02082e0d181f2c54c1c72d2dc0a7ce358`).  Full releases are tagged in Git and their version number is used in the release (e.g. the tag `v0.2` would correspond to version `0.2`).  You can always [browse the Bintray repository](https://bintray.com/djspiewak/maven/sparse/view) to see what is available and when it was pushed.

Sparse is currently dependent on Scala 2.11, and so no cross-builds have been published for 2.10.  Sparse is also like, a day old, so no full releases have been published as of 31/12/2014.

Releases are always signed by [Daniel Spiewak](https://keybase.io/djspiewak) (`3587 7FB3 2BAE 5960`).  Practice good Maven artifact hygene!  Import the trusted public key and use the `check-pgp-signatures` task provided by sbt-pgp to ensure the validity of the JARs you're chucking onto your classpath.

## Parser Construction

A combinatorial DSL is provided for constructing new instances of `Parser`.  The following familiar combinators are implemented:

- `~` – Sequences two parsers together such that the resulting parser returns a pair of the left and right result, in order
- `|` – Disjunctively composes two parsers such that the resulting parser returns a result from *either* the left or the right side, whichever parses the input successfully
- `~>` – Similar to `~`, except it discards the left result
- `<~` – Similar to `~`, except it discards the right result
- `^^` – A type-specialized `map` operation.  Takes a function which maps the result type of the input parser to a new result type.  Note that this operator implicitly understands the pair structure returned by the `~` combinator and requires a function with matching arity.  A non-specialized `map` function is also available (`Functor[Parser]` is defined)

An important side note is that the `|` operator is *true* union.  In formal terms, the language generated by the CFG represented by the left-hand side is unioned with the language generated by the CFG represented by the right-hand side.  At present, *global* ambiguity in parse results is disallowed (it will produce an error).  Local ambiguity works as expected.

The base-case functions for building parsers generate simple, one-or-zero token parsers from primitive input.  These functions are as follows (note that all of these functions are defined within the `Parser` object):

- `completed[T, R] : R => Parser[T, R]` – Produces a parser which accepts the empty string
- `error[T, R] : String => Parser[T, R]` – Produces a parser which generates a given error on all input (sometimes useful for debugging grammars)
- `literal[T: Equal: Show] : T => Parser[T, T]` – Produces a parser which accepts the specified token exactly once, producing that token as a result
- `pattern[T: Show, R] : PartialFunction[T, R] => Parser[T, R]` – Lifts a `PartialFunction` into a `Parser`.  Don't do bad things with this function; it's intended for simple token matching only!

All of the combinators are defined on implicit classes in the `Parser` companion object, and the type inference is such that the `Parser` target type is always strictly inferrabdle.  Thus, you shouldn't have to worry about polution of the implicit scope (in other words, the `import Parser._` in the example up top is a bit of an anti-pattern).  *However*, if you want all of the fancy syntax to work, you will need to import a special implicit conversion which operates directly on `Token: Equal: Show`.  Specifically, this conversion is `Parser.literalRichParser`.  This conversion enables the use of the standard combinators directly on literal token types as if they had been pre-lifted with the `literal` function.  In essence, it defines implicit chaining from any `Token` type to something that behaves like a `Parser`.

It's worth noting that `Parser` is invariant in both its parameter types, which can sometimes get you into trouble when composing grammars with tokens that are instances of some `sealed trait` (very common!).  This means that you may occaisionally have to type-ascribe your literals in order to make the DSL work.

## Tokenization

The `Parser` type has two type parameters: `Token` and `Result`.  Thus, unlike most parser combinator libraries, it is *not* specialized on streams of characters!  For example, we can reimplement our parentheses grammar in terms of binary integers:

```scala
lazy val parens: Parser[Int, Int] = (
	0 ~> parens <~ 1 ^^ (1 +)
  | completed(0)
)

Process(0, 0, 0, 1, 1, 1, 0, 1, 0, 0, 1, 1) pipe parse(parens) stripW		// => Process(3, 1, 2, 0)
```

It really doesn't matter what token type you're using!  This is tremendously powerful in several ways.  For example, you can actually chain multiple grammars together, each representing a different processing phase:

```scala
(input pipe parse(phase1) stripW) pipe parse(phase2) stripW
```

In the above, as long as the `phase2` parser has a token type that is compatible with the result type of `phase1`, you will be able to chain them in this fashion.  This can be tremendously, ridiculously powerful.  (citation needed)

One very useful application of this is moving your fast regular expression tokenization into a pre-processing pass over the input stream.  While it is *technically* possible to construct a scannerless `Parser[Char, Result]` instance which uses the `pattern` constructor to perform regular expression matching, it really isn't recommended.  `Parser` is *slow*.  Very, very slow.  It also has very limited error recovery (more on this in a bit).  A separate tokenization pass directly on `Process` is a much more efficient option.  The test suite has some examples of how this can be done.

One theoretically-interesting observation…  Due to the fact that `Parser` is parametric in its `Token` type and makes absolutely no assumptions other than having an `Equal` and a `Show` instance (or just `Show`, if you're using `pattern`), it is technically possible to use this framework to encode any *computation* over an input `Process` that can be represented by a [pushdown automaton](https://en.wikipedia.org/wiki/Pushdown_automaton)!  I have no idea where this would be useful, but it seems extremely cool.

## Errors

Inevitably, errors arise in parsing.  When this happens, the `parse` evaluator will *not* abort the input stream!  Let's take a closer look at the type signature for `parse`:

```scala
def parse[T: Show, R](parser: Parser[T, R]): Process1[T, String \/ R]
```

When you `pipe` an input `Process[F, T]` through a `Parser[T, R]`, it produces a `Writer[F, String, R]`, where the `String` side of the writer represents error output.  This is why a `Show` instance is required in various places.  Could we have a better, saner error type?  Oooooooh yeah.  We just don't right now.

Moving on…  The `Process1` returned from `parse` will consume input tokens from the stream and feed them to the given `Parser`.  After every token, it will *attempt* to complete the parse early (this is the greedy part of the evaluator).  If it cannot complete the parse, it will continue consuming until it can or until it runs out of input.  *However*, if the parser has reached a state where no further tokens can produce a valid output (e.g. mismatched parentheses in the examples above), the evaluator will immediately halt its current parse trail and produce a error `String` representing the fault.  These error strings are *usually* pretty informative, but not always.  Once the error `String` has been emitted, the final token which led to the error is discarded and the input parser is restarted just as if it had completed a parse successfully.

Thus, the `parse` evaluator has a very simple error recovery mechanism built into it.  The error recovery is best described as "when in doubt, toss it out!"  This isn't *necessarily* the best idea, but it does work and it was easy to implement.  Other strategies are feasible.

So anyway, if you want to respond to the errors produced, simply process the *write* side of the `Writer`.  If you want to discard the errors (as my examples thus far have done), you can use the `stripW` combinator provided by scalaz-stream.

## Algorithmic Properties

Under the surface, this whole thing is implemented atop a fresh implementation of [derivative parsing](http://matt.might.net/articles/parsing-with-derivatives/).  Specifically, the representation has been re-tooled slightly so that the `derive` function and its associated state are exposed externally.  This can be seen on the `derive` method of `Incomplete`, a sealed subtype of `Parser`:

```scala
def derive(t: Token): State[Cache[Token], Parser[Token, Result]]
```

Derivative parsing, intuitively, implements a *breadth-first* form of recursive-descent parsing.  Rather than starting from the top of the tree and exploring each branch in turn, as does recursive-descent (and by extension, traditional parser combinators), derivative parsing starts with a set of all possible leaves and progressively prunes the tree.  This is extremely nice in several ways, one of the most notable for the purposes of stream parsing being that backtracking is indirectly represented via the pruning!

Traditional parser combinators, and raw recursive-descent, must maintain a pointer to a previous state of the stream in order to backtrack out of a disjunction should the attempted branch prove erroneous.  This is of course very problematic to encode on top of `Process`, since streams are ephemeral and previous states no longer exist when you have consumed further!  Derivative parsers bypass this problem completely.  Backtracking still exists in *effect*, but it is indirectly and implicitly encoded in the traversal and pruning of the tree of all possible parse trails.  Thus, the algorithm *itself* memoizes exactly and only the elements of the input stream which may be important for subsequent derivation.  As soon as those parse trails are falsified, the memoized values drop out of memory completely.  Thus, while in the worst case, the entire input stream (since the last valid result) may be held in memory all at once, practically very very little of the input stream will be memoized and that memoization will be aggressively pruned.  Within the bounds of the stack-based nature of a pushdown automaton, this algorithm is essentially heap-constant.

Derivative parsing is also nice in that it implements a fully general parsing algorithm, capable of recognizing *any* context-free grammar.  This includes traditionally-tricky features like left-recursion and ambiguity.  For reasons of convenience (and sanity!) in the representation, *global* ambiguity is forbidden (thus, you cannot have a grammar which recognizes a complete output result in two distinct ways).  However, local ambiguity *is* allowed, which enables some very convenient grammatical constructions.  The infamous "dangling `else` problem", for example, simply does not exist with derivative parsing.  Assuming your grammar is correctly written (e.g. Java's), a dangling else "ambiguity" becomes a strictly local ambiguity which is factored out at either the immediate end of the stream or the appearance of a subsequent token.

Unfortunately, all this generality and convenience of implementation does come at a cost: derivative parsing is worst-case *O(k^n)*, where *n* is the number of tokens since the last valid result and *k* is a coefficient linearly correlated with the number of disjunctions in your grammar and the number of separable tokens in your lexicon.  In practice, the pathologies which trigger the exponential runtimes are somewhat rare.  Generally speaking, you can rely on *roughly* quadratic runtime and close to linear memory.

There is a proof sketch that derivative parsing is in fact *inherently* exponential time.  Even better than a proof sketch, if you examine the source code for `parsers.scala`, you can see a comment where I note exactly where the algorithm performs a comparison that under-matches its desired criterion, resulting in less effective memoization and longer runtime.  What it boils down to is the fact that the equality test for generalized context-free grammars (as defined by equality of the generated languages) is an undecidable problem.  If equality *were* decidable on CFGs, derivative parsing would be a worst-case quadratic algorithm, which is kind of cool.

## Ongoing

Uh, I'm still working on stuff.  The test suite is about the furthest you can get from "comprehensive" and still have any self respect.  I want to extend the `parse` evaluator a bit to allow for non-greedy strategies (the greedy strategy has some disadvantages, especially with suffix terms).  Performance is probably somewhere between "awful" and "Ctrl-C", so keep that in mind if you play with things.

## Legal

This project (and all contributions) are licensed under the Apache License v2.0.  Copyright is held by Daniel Spiewak, just to avoid insanity, confusion and despair.  Opening a pull request implies that you agree to license your contributions under the Apache License v2.0.  Don't open a pull request if you don't know what this means.  For more details, see the `LICENSE.txt` file.
