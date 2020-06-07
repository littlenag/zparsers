package playground

/**
 * @author Mark Kegel (mkegel@vast.com)
 */
object FastParsePlayground {

  import fastparse._
  import NoWhitespace._

  def binary(implicit ev: P[_]) = P( ("0" | "1" ).rep.! )
  def binaryNum[_: P] = P( binary.map(Integer.parseInt(_, 2)) )

  val Parsed.Success("1100", _) = parse("1100", x => binary(x))
  val Parsed.Success(12, _) = parse("1100", binaryNum(_))

  List(1,2,3).filter(_ > 2)
}
