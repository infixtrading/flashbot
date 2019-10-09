package examples

object MixinExample extends App {

  trait DataHandler {
    def _handle(): Unit
  }

  trait TSHandler extends DataHandler { self: Strat =>
    abstract override def _handle(): Unit = {
      println(s"TS handle: ${self.greeting}")
      super._handle()
    }
  }

  abstract class Strat extends DataHandler {
    def handle(): Unit
    def greeting = "SUP"
    override def _handle() = {
      handle()
    }
  }

  class Foo extends Strat with TSHandler {

    override def handle(): Unit = {
      println("My handle")
    }
  }

  val foo = new Foo

  foo._handle()

  println(Seq("a", "b").splitAt(-1))
  println(Seq("a", "b").splitAt(0))
  println(Seq("a", "b").splitAt(1))
  println(Seq("a", "b").splitAt(5))
}
