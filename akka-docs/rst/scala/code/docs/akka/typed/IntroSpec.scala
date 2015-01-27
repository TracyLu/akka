/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.akka.typed

//#imports
import akka.typed._
import akka.typed.ScalaDSL._
import akka.typed.AskPattern._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.Await
//#imports
import akka.testkit.AkkaSpec
import akka.typed.TypedSpec

object IntroSpec {

  //#hello-world-actor
  object HelloWorld {
    final case class Greet(whom: String, replyTo: ActorRef[Greeted])
    final case class Greeted(whom: String)

    val greeter = Static[Greet] { msg ⇒
      println(s"Hello ${msg.whom}!")
      msg.replyTo ! Greeted(msg.whom)
    }
  }
  //#hello-world-actor

  //#counter-actor
  object Counter {

    sealed trait Command
    final case class GetAndAdd(amount: Int, replyTo: ActorRef[CurrentCount]) extends Command
    final case class AddAndGet(amount: Int, replyTo: ActorRef[CurrentCount]) extends Command

    final case class CurrentCount(x: Int)

    val counter: Behavior[Command] = behavior(0)

    private def behavior(count: Int): Behavior[Command] =
      Total {
        case AddAndGet(amount, replyTo) ⇒
          val nextCount = count + amount
          replyTo ! CurrentCount(nextCount)
          behavior(nextCount)
        case GetAndAdd(amount, replyTo) ⇒
          replyTo ! CurrentCount(count)
          behavior(count + amount)
      }
  }
  //#counter-actor

  //#chatroom-actor
  object ChatRoom {
    //#chatroom-protocol
    sealed trait Command
    final case class GetSession(screenName: String, replyTo: ActorRef[SessionEvent])
      extends Command
    //#chatroom-protocol
    //#chatroom-behavior
    private final case class PostSessionMessage(screenName: String, message: String)
      extends Command
    //#chatroom-behavior
    //#chatroom-protocol

    sealed trait SessionEvent
    final case class SessionGranted(handle: ActorRef[PostMessage]) extends SessionEvent
    final case class SessionDenied(reason: String) extends SessionEvent
    final case class MessagePosted(screenName: String, message: String) extends SessionEvent

    final case class PostMessage(message: String)
    //#chatroom-protocol
    //#chatroom-behavior

    val behavior: Behavior[GetSession] =
      ContextAware[Command] { ctx ⇒
        var sessions = List.empty[ActorRef[SessionEvent]]

        Static {
          case GetSession(screenName, client) ⇒
            sessions ::= client
            val wrapper = ctx.createWrapper {
              p: PostMessage ⇒ PostSessionMessage(screenName, p.message)
            }
            client ! SessionGranted(wrapper)
          case PostSessionMessage(screenName, message) ⇒
            val mp = MessagePosted(screenName, message)
            sessions foreach (_ ! mp)
        }
      }.narrow // only expose GetSession to the outside
    //#chatroom-behavior
  }
  //#chatroom-actor

}

class IntroSpec extends TypedSpec {
  import IntroSpec._

  def `must say hello`(): Unit = {
    //#hello-world
    import HelloWorld._
    import scala.concurrent.ExecutionContext.Implicits.global

    val system: ActorSystem[Greet] = ActorSystem("hello", Props(greeter))

    val future: Future[Greeted] = system ? (Greet("world", _))

    for {
      greeting <- future
      done <- { println(s"result: $greeting"); system.terminate() }
    } println("system terminated")
    //#hello-world
  }

  def `must count with partner`(): Unit = {
    //#counter-become
    import Counter._

    // first expect a current count of 0
    def first(c: ActorRef[Command]) = {
      Full[CurrentCount] {
        case Msg(ctx, current) ⇒
          println(s"current count was ${current.x}")
          c ! AddAndGet(6, ctx.self)
          second(c)
      }
    }
    // then expect a changed count of 11
    def second(c: ActorRef[Command]) =
      Total[CurrentCount] { current ⇒
        println(s"now the count was ${current.x}")
        Stopped
      }

    val system = ActorSystem("counterBecome", Props(Full[CurrentCount] {
      case Sig(ctx, PreStart) ⇒
        val c = ctx.spawn(Props(counter), "myCounter")
        c ! GetAndAdd(5, ctx.self)
        first(c)
    }))
    //#counter-become
  }

  def `must count with StepWise`(): Unit = {
    //#counter-stepwise
    import Counter._

    val tester =
      StepWise[CurrentCount] { (ctx, startWith) ⇒
        val countRef = ctx.spawn(Props(counter), "counter")

        startWith {
          countRef ! GetAndAdd(5, ctx.self)
        }.expectMessage(500.millis) { (current, _) ⇒
          println(s"current count was ${current.x}") // prints 0
          countRef ! AddAndGet(6, ctx.self)
        }.expectMessage(500.millis) { (current, _) ⇒
          println(s"now the count was ${current.x}") // prints 11
        }
      }

    val system = ActorSystem("counterTest", Props(tester))
    Await.result(system.whenTerminated, 2.seconds)
    //#counter-stepwise
  }

  def `must chat`(): Unit = {
    //#chatroom-gabbler
    import ChatRoom._

    val gabbler: Behavior[SessionEvent] =
      Total {
        case SessionDenied(reason) ⇒
          println(s"cannot start chat room session: $reason")
          Stopped
        case SessionGranted(handle) ⇒
          handle ! PostMessage("Hello World!")
          Same
        case MessagePosted(screenName, message) ⇒
          println(s"message has been posted by '$screenName': $message")
          Stopped
      }
    //#chatroom-gabbler

    //#chatroom-main
    val main: Behavior[Unit] =
      Full {
        case Sig(ctx, PreStart) ⇒
          val chatRoom = ctx.spawn(Props(ChatRoom.behavior), "chatroom")
          val gabblerRef = ctx.spawn(Props(gabbler), "gabbler")
          ctx.watch(gabblerRef)
          chatRoom ! GetSession("ol’ Gabbler", gabblerRef)
          Same
        case Sig(_, Terminated(ref)) ⇒
          Stopped
      }

    val system = ActorSystem("ChatRoomDemo", Props(main))
    Await.result(system.whenTerminated, 1.second)
    //#chatroom-main
  }

}
