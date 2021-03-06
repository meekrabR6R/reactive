package nodescala

import scala.language.postfixOps
import scala.util.{Try, Success, Failure}
import scala.collection._
import scala.concurrent._
import ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.async.Async.{async, await}
import org.scalatest._
import NodeScala._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class NodeScalaSuite extends FunSuite {

  test("A Future should always be completed") {
    val always = Future.always(517)

    assert(Await.result(always, 0 nanos) == 517)
  }

  test("A Future should never be completed") {
    val never = Future.never[Int]

    try {
      Await.result(never, 1 second)
      assert(false)
    } catch {
      case t: TimeoutException => // ok!
    }
  }

  test("A Future holding the list of values of all the Futures from a List[Future[T]].") {
    val fs = List(Future(1), Future(2), Future(3))
    val res = Await.result(Future.all(fs), 3000000 nanos)
    //println(Await.result(Future.all(fs), 3000000 nanos))
    assert(res == List(1,2,3))
  }

  test("A race beween Futures should return the quickest Future[T]") {
    val res = Await.result(Future.any(List(Future(blocking { Thread.sleep(1000); 1}),
                              Future(blocking { Thread.sleep(100); 2 }),
                              Future(blocking { Thread.sleep(10); 3 }))), 30000000 nanos)
    assert(res == 3)
  }

  test("Future.delay have to finish when Await will wait") {
    val delayFuture = Future.delay(1 second)

    try {
      Await.result(delayFuture, 2 seconds)
    } catch {
      case t: TimeoutException => assert(false, "This should not timed out!")
    }
  }

  test("Future.delay can't to finish when Await won't wait") {
    val delayFuture = Future.delay(2 seconds)

    try {
      Await.result(delayFuture, 1 second)
      assert(false, "This should not be here!")
    } catch {
      case t: TimeoutException =>
    }
  }

  test("Calling 'now' on instances of Future could return the Future's value..") {
    val f = Future(2)
    assert(f.now == 2)

    val delay = Future.delay(5 seconds)
    intercept[NoSuchElementException] { delay.now }
  }

  test("continueWith should wait for the first future to complete") {
    val delay = Future.delay(1 second)
    val always = (f: Future[Unit]) => 42

    try {
      Await.result(delay.continueWith(always), 500 millis)
      assert(false)
    }
    catch {
      case t: TimeoutException => // ok
    }
  }

  test("continue should wait for the first future to complete") {
    val delay = Future.delay(1 second)
    val always = (f: Try[Unit]) => 42

    try {
      Await.result(delay.continue(always), 500 millis)
      assert(false)
    }
    catch {
      case t: TimeoutException => // ok
    }
  }

  test("CancellationTokenSource should allow stopping the computation") {
    val p = Promise[String]()

    val cts = Future.run() { ct =>
      async {
        while (ct.nonCancelled) {
          // do work
        }

        p.success("done")
      }
    }
    cts.unsubscribe()
    assert(Await.result(p.future, 1 second) == "done")
  }

  test("Server should be stoppable if receives infinite  response") {
    val dummy = new DummyServer(8191)
    val dummySubscription = dummy.start("/testDir") {
      request => Iterator.continually("a")
    }

    // wait until server is really installed
    Thread.sleep(500)

    val webpage = dummy.emit("/testDir", Map("Any" -> List("thing")))
    try {
      // let's wait some time
      Await.result(webpage.loaded.future, 1 second)
      fail("infinite response ended")
    } catch {
      case e: TimeoutException =>
    }

    // stop everything
    dummySubscription.unsubscribe()
    Thread.sleep(500)
    webpage.loaded.future.now // should not get NoSuchElementException
  }

  class DummyExchange(val request: Request) extends Exchange {
    @volatile var response = ""
    val loaded = Promise[String]()
    def write(s: String) {
      response += s
    }
    def close() {
      loaded.success(response)
    }
  }

  class DummyListener(val port: Int, val relativePath: String) extends NodeScala.Listener {
    self =>

    @volatile private var started = false
    var handler: Exchange => Unit = null

    def createContext(h: Exchange => Unit) = this.synchronized {
      assert(started, "is server started?")
      handler = h
    }

    def removeContext() = this.synchronized {
      assert(started, "is server started?")
      handler = null
    }

    def start() = self.synchronized {
      started = true
      new Subscription {
        def unsubscribe() = self.synchronized {
          started = false
        }
      }
    }

    def emit(req: Request) = {
      val exchange = new DummyExchange(req)
      if (handler != null) handler(exchange)
      exchange
    }
  }

  class DummyServer(val port: Int) extends NodeScala {
    self =>
    val listeners = mutable.Map[String, DummyListener]()

    def createListener(relativePath: String) = {
      val l = new DummyListener(port, relativePath)
      listeners(relativePath) = l
      l
    }

    def emit(relativePath: String, req: Request) = this.synchronized {
      val l = listeners(relativePath)
      l.emit(req)
    }
  }

  test("Server should serve requests") {
    val dummy = new DummyServer(8191)
    val dummySubscription = dummy.start("/testDir") {
      request => for (kv <- request.iterator) yield (kv + "\n").toString
    }

    // wait until server is really installed
    Thread.sleep(500)

    def test(req: Request) {
      val webpage = dummy.emit("/testDir", req)
      val content = Await.result(webpage.loaded.future, 1 second)
      val expected = (for (kv <- req.iterator) yield (kv + "\n").toString).mkString
      assert(content == expected, s"'$content' vs. '$expected'")
    }

    test(immutable.Map("StrangeRequest" -> List("Does it work?")))
    test(immutable.Map("StrangeRequest" -> List("It works!")))
    test(immutable.Map("WorksForThree" -> List("Always works. Trust me.")))

    dummySubscription.unsubscribe()
  }

}




