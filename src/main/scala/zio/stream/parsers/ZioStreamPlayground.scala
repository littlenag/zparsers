package zio.stream.parsers

/**
  * @author Mark Kegel (mkegel@vast.com)
  */
object ZioStreamPlayground {

  import zio.stream._

  val intStream: Stream[Nothing, Int] = Stream.fromIterable(0 to 100)
  val stringStream: Stream[Nothing, String] = intStream.map(_.toString)

  val s = ZSink.sum[Int]

  val x = intStream.run(s)


  intStream.broadcast()

  //intStream.aggregateAsyncWithin()

  //intStream.via()

}
