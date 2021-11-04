package vbosiak.worker.helpers

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.cluster.typed.{Cluster, Leave}
import vbosiak.common.utils.Clock
import vbosiak.common.utils.FieldFormatter._
import vbosiak.worker.actors.Worker._

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.immutable.ArraySeq
import scala.collection.parallel.CollectionConverters._
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

private[worker] trait WorkerHelper {
  implicit val context: ActorContext[WorkerCommand]

  def handleDieCommand(reason: String): Behavior[WorkerCommand] = {
    val cluster = Cluster(context.system)
    context.log.error("Received poison pill from master because of \"{}\"", reason)
    cluster.manager ! Leave(cluster.selfMember.address)
    Behaviors.same
  }

  def handleWrongCommand(command: WorkerCommand, currentBehaviourName: String): Behavior[WorkerCommand] = {
    context.log.warn("Received {} in {} behaviour: {}", command, currentBehaviourName)
    Behaviors.same
  }

  def handleShowCommand(field: Field): Behavior[WorkerCommand] = {
    context.log.info("Received show command:\n{}", field.beautify)
    Behaviors.same
  }

  def handleNewSimulationCommand(command: NewSimulation, initialBehaviour: Behavior[WorkerCommand]): Behavior[WorkerCommand] = {
    context.log.info("Resetting self to initial state and start preparing new simulation")
    context.self ! command
    initialBehaviour
  }

  def computeNextIteration(
      field: Field,
      leftSide: Side,
      rightSide: Side,
      standAlone: Boolean = false
  )(implicit ec: ExecutionContext): Future[(Field, Int, FiniteDuration)] = Future {
    val ((finishedField, population), duration) = Clock.withMeasuring {

      val fieldCopy  = field.map(_.toArray)
      val population = new AtomicInteger(0)

      field.zipWithIndex.par.foreach { case (row, r) =>
        for (c <- row.indices) {
          val isAlive = row(c)

          val aliveNeighbors: Int = Seq(
            computeCell(field, r - 1, c, standAlone),                // top
            computeCell(field, r + 1, c, standAlone),                // bottom
            computeCell(field, r, c + 1, standAlone, rightSide),     // right
            computeCell(field, r, c - 1, standAlone, leftSide),      // left
            computeCell(field, r - 1, c + 1, standAlone, rightSide), // top-right
            computeCell(field, r - 1, c - 1, standAlone, leftSide),  // top-left
            computeCell(field, r + 1, c + 1, standAlone, rightSide), // bottom-right
            computeCell(field, r + 1, c - 1, standAlone, leftSide)   // bottom-left
          ).count(identity)

          if (isAlive && (aliveNeighbors == 2 || aliveNeighbors == 3))
            population.incrementAndGet()
          else if (!isAlive && aliveNeighbors == 3) {
            fieldCopy(r)(c) = true
            population.incrementAndGet()
          } else
            fieldCopy(r)(c) = false
        }
      }

      fieldCopy.map(_.to(ArraySeq)) -> population.get()
    }
    (finishedField, population, duration)
  }

  def computeCell(field: Field, r: Int, c: Int, standAlone: Boolean, neighborSide: Side = Nil): Boolean =
    (field.isDefinedAt(r), field.head.isDefinedAt(c)) match {
      // Cases in both modes
      case (true, true)  => field(r)(c)
      case (false, true) => if (r >= field.size) field.head(c) else field.last(c)

      // Cases in multi-worker mode
      case (true, false) if !standAlone  => neighborSide(r)
      case (false, false) if !standAlone => neighborSide.head

      // Cases in stand-alone mode
      case (true, false)                                             => if (c >= field.head.size) field(r).head else field(r).last
      case (false, false) if r >= field.size && c >= field.head.size => field.head.head
      case (false, false) if r < field.size && c >= field.head.size  => field.last.head
      case (false, false) if r >= field.size && c < field.head.size  => field.head.last
      case (false, false) if r < field.size && c < field.head.size   => field.last.last
    }

  def generateStableTestSample: Field = {
    import vbosiak.common.utils.FieldPainter._

    A(
      A(-, -, o, o, -, -, o, o, -, -),
      A(-, -, -, -, -, -, -, -, -, -),
      A(o, -, -, -, -, -, -, -, -, o),
      A(o, -, -, -, -, -, -, -, -, o),
      A(-, -, -, -, o, o, -, -, -, -),
      A(o, -, -, -, o, o, -, -, -, o),
      A(o, -, -, -, -, -, -, -, -, o),
      A(-, -, -, -, -, -, -, -, -, -),
      A(-, -, -, -, -, -, -, -, -, -),
      A(-, -, o, o, -, -, o, o, -, -)
    )
  }
}
