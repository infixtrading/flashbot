//
//object ImplicitsByName extends App {
//  trait Foo {
//    def next: Foo
//  }
//  object Foo {
//    implicit def foo(implicit rec: => Foo): Foo = new Foo { def next = rec }
//  }
//
//  val yo = "yo"
//
////  val foo: Foo = implicitly[Foo]
////  assert(foo eq foo.next)
//}
