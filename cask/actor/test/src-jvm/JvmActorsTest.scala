package cask.actor

import utest._
object JvmActorsTest extends TestSuite{
  def tests = Tests{
    os.remove.all(os.pwd / "out" / "scratch")
    test("lock"){
      val rotateSize = 50
      val logPath = os.pwd / "out" / "scratch" / "log.txt"
      val oldPath = os.pwd / "out" / "scratch" / "log-old.txt"

      var logSize = 0

      def logLine(s: String): Unit = synchronized{
        val newLogSize = logSize + s.length + 1
        if (newLogSize <= rotateSize) logSize = newLogSize
        else {
          logSize = 0
          os.move(logPath, oldPath, replaceExisting = true)
        }

        os.write.append(logPath, s + "\n", createFolders = true)
      }

      logLine("I am cow")
      logLine("hear me moo")
      logLine("I weight twice as much as you")
      logLine("And I look good on the barbecue")
      logLine("Yoghurt curds cream cheese and butter")
      logLine("Comes from liquids from my udder")
      logLine("I am cow, I am cow")
      logLine("Hear me moo, moooo")

      os.read(oldPath).trim() ==> "Yoghurt curds cream cheese and butter\nComes from liquids from my udder"
      os.read(logPath).trim() ==> "I am cow, I am cow\nHear me moo, moooo"
    }

    test("actor"){
      class Logger(log: os.Path, old: os.Path, rotateSize: Int)
                  (implicit ac: Context) extends SimpleActor[String]{
        def run(s: String) = {
          val newLogSize = logSize + s.length + 1
          if (newLogSize <= rotateSize) logSize = newLogSize
          else {
            logSize = s.length
            os.move(log, old, replaceExisting = true)
          }
          os.write.append(log, s + "\n", createFolders = true)
        }
        private var logSize = 0
      }

      implicit val ac = new Context.Test()

      val logPath = os.pwd / "out" / "scratch" / "log.txt"
      val oldPath  = os.pwd / "out" / "scratch" / "log-old.txt"

      val logger = new Logger(logPath, oldPath, rotateSize = 50)

      logger.send("I am cow")
      logger.send("hear me moo")
      logger.send("I weight twice as much as you")
      logger.send("And I look good on the barbecue")
      logger.send("Yoghurt curds cream cheese and butter")
      logger.send("Comes from liquids from my udder")
      logger.send("I am cow, I am cow")
      logger.send("Hear me moo, moooo")

      // Logger hasn't finished yet, running in the background
      ac.waitForInactivity()
      // Now logger has finished

      os.read.lines(oldPath) ==> Seq("Comes from liquids from my udder")
      os.read.lines(logPath) ==> Seq("I am cow, I am cow", "Hear me moo, moooo")
    }

    test("pipeline"){
      class Writer(log: os.Path, old: os.Path, rotateSize: Int)
                  (implicit ac: Context) extends SimpleActor[String]{
        def run(s: String) = {
          val newLogSize = logSize + s.length + 1
          if (newLogSize <= rotateSize) logSize = newLogSize
          else {
            logSize = s.length
            os.move(log, old, replaceExisting = true)
          }
          os.write.append(log, s + "\n", createFolders = true)
        }
        private var logSize = 0
      }

      class Logger(dest: Actor[String])(implicit ac: Context) extends SimpleActor[String]{
        def run(s: String) = dest.send(java.util.Base64.getEncoder.encodeToString(s.getBytes))
      }

      implicit val ac = new Context.Test()

      val logPath = os.pwd / "out" / "scratch" / "log.txt"
      val oldPath  = os.pwd / "out" / "scratch" / "log-old.txt"

      val writer = new Writer(logPath, oldPath, rotateSize = 50)
      val logger = new Logger(writer)

      logger.send("I am cow")
      logger.send("hear me moo")
      logger.send("I weight twice as much as you")
      logger.send("And I look good on the barbecue")
      logger.send("Yoghurt curds cream cheese and butter")
      logger.send("Comes from liquids from my udder")
      logger.send("I am cow, I am cow")
      logger.send("Hear me moo, moooo")

      ac.waitForInactivity()

      os.read(oldPath) ==> "Q29tZXMgZnJvbSBsaXF1aWRzIGZyb20gbXkgdWRkZXI=\n"
      os.read(logPath) ==> "SSBhbSBjb3csIEkgYW0gY293\nSGVhciBtZSBtb28sIG1vb29v\n"

      def decodeFile(p: os.Path) = {
        os.read.lines(p).map(s => new String(java.util.Base64.getDecoder.decode(s)))
      }

      decodeFile(oldPath) ==> Seq("Comes from liquids from my udder")
      decodeFile(logPath) ==> Seq("I am cow, I am cow", "Hear me moo, moooo")
    }

    test("debounce"){
      sealed trait Msg
      case class Debounced() extends Msg
      case class Text(value: String) extends Msg

      class Logger(log: os.Path, debounceTime: java.time.Duration)
                  (implicit ac: Context) extends StateMachineActor[Msg]{
        def initialState = Idle()
        case class Idle() extends State({
          case Text(value) =>
            ac.scheduleMsg(this, Debounced(), debounceTime)
            Buffering(Vector(value))
        })
        case class Buffering(buffer: Vector[String]) extends State({
          case Text(value) => Buffering(buffer :+ value)
          case Debounced() =>
            os.write.append(log, buffer.mkString(" ") + "\n", createFolders = true)
            Idle()
        })
      }

      implicit val ac = new Context.Test()

      val logPath = os.pwd / "out" / "scratch" / "log.txt"

      val logger = new Logger(logPath, java.time.Duration.ofMillis(50))

      logger.send(Text("I am cow"))
      logger.send(Text("hear me moo"))
      Thread.sleep(100)
      logger.send(Text("I weight twice as much as you"))
      logger.send(Text("And I look good on the barbecue"))
      Thread.sleep(100)
      logger.send(Text("Yoghurt curds cream cheese and butter"))
      logger.send(Text("Comes from liquids from my udder"))
      logger.send(Text("I am cow, I am cow"))
      logger.send(Text("Hear me moo, moooo"))

      ac.waitForInactivity()

      os.read.lines(logPath) ==> Seq(
        "I am cow hear me moo",
        "I weight twice as much as you And I look good on the barbecue",
        "Yoghurt curds cream cheese and butter Comes from liquids from my udder I am cow, I am cow Hear me moo, moooo",
      )
    }

  }
}