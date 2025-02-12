package zio.slides

import zio._
import zio.clock.Clock
import zio.console.Console
import zio.duration.durationInt
import zio.slides.VoteState.{CastVoteId, UserId}
import zio.stream._

/** Improvements:
  *
  * - Add a VoteStateRef. Send the current Vote State to a user
  *   when they join.
  *
  * - When a user disconnects, remove their votes.
  */
trait SlideApp {
  def slideStateStream: UStream[SlideState]
  def questionStateStream: UStream[QuestionState]
  def voteStream: UStream[Chunk[CastVoteId]]
  def populationStatsStream: UStream[PopulationStats]

  def receiveUserCommand(id: UserId, userCommand: UserCommand): UIO[Unit]
  def receiveAdminCommand(adminCommand: AdminCommand): UIO[Unit]

  def userJoined: UIO[Unit]
  def userLeft: UIO[Unit]
}

object SlideApp {
  val live: URLayer[Clock with Console, Has[SlideApp]] = SlideAppLive.layer

  // Accessor Methods

  def slideStateStream: ZStream[Has[SlideApp], Nothing, SlideState] =
    ZStream.accessStream[Has[SlideApp]](_.get.slideStateStream)

  def questionStateStream: ZStream[Has[SlideApp], Nothing, QuestionState] =
    ZStream.accessStream[Has[SlideApp]](_.get.questionStateStream)

  def voteStream: ZStream[Has[SlideApp], Nothing, Chunk[CastVoteId]] =
    ZStream.accessStream[Has[SlideApp]](_.get.voteStream)

  def populationStatsStream: ZStream[Has[SlideApp], Nothing, PopulationStats] =
    ZStream.accessStream[Has[SlideApp]](_.get.populationStatsStream)

  def receiveUserCommand(id: UserId, userCommand: UserCommand): ZIO[Has[SlideApp], Nothing, Unit] =
    ZIO.accessM[Has[SlideApp]](_.get.receiveUserCommand(id, userCommand))

  def receiveAdminCommand(adminCommand: AdminCommand): ZIO[Has[SlideApp], Nothing, Unit] =
    ZIO.accessM[Has[SlideApp]](_.get.receiveAdminCommand(adminCommand))

  def userJoined: ZIO[Has[SlideApp], Nothing, Unit] =
    ZIO.accessM[Has[SlideApp]](_.get.userJoined)

  def userLeft: ZIO[Has[SlideApp], Nothing, Unit] =
    ZIO.accessM[Has[SlideApp]](_.get.userLeft)
}

case class SlideAppLive(
    slideStateRef: RefM[SlideState],
    slideStateStream: UStream[SlideState],
    questionStateRef: RefM[QuestionState],
    questionStateStream: UStream[QuestionState],
    voteQueue: Queue[CastVoteId],
    voteStream: UStream[Chunk[CastVoteId]],
    populationStatsRef: RefM[PopulationStats],
    populationStatsStream: UStream[PopulationStats]
) extends SlideApp {

  def receiveAdminCommand(adminCommand: AdminCommand): UIO[Unit] =
    adminCommand match {
      case AdminCommand.NextSlide => slideStateRef.update(s => UIO(s.nextSlide))
      case AdminCommand.PrevSlide => slideStateRef.update(s => UIO(s.prevSlide))
      case AdminCommand.NextStep  => slideStateRef.update(s => UIO(s.nextStep))
      case AdminCommand.PrevStep  => slideStateRef.update(s => UIO(s.prevStep))
      case AdminCommand.ToggleQuestion(id) =>
        questionStateRef.update(qs => UIO(qs.toggleQuestion(id)))
    }

  def receiveUserCommand(id: UserId, userCommand: UserCommand): UIO[Unit] =
    userCommand match {
      case UserCommand.AskQuestion(question, slideIndex) =>
        questionStateRef.update(qs => UIO(qs.askQuestion(question, slideIndex)))
      case UserCommand.SendVote(topic, vote) =>
        voteQueue.offer(CastVoteId(id, topic, vote)).unit
    }

  override def userLeft: UIO[Unit] =
    populationStatsRef.update(stats => UIO(stats.removeOne))

  override def userJoined: UIO[Unit] =
    populationStatsRef.update(stats => UIO(stats.addOne))
}

object SlideAppLive {
  val layer: ZLayer[Clock with Console, Nothing, Has[SlideApp]] = {
    for {
      slideVar           <- SubscriptionRef.make(SlideState.empty).toManaged_
      questionsVar       <- SubscriptionRef.make(QuestionState.empty).toManaged_
      populationStatsVar <- SubscriptionRef.make(PopulationStats(0)).toManaged_

      voteQueue  <- Queue.bounded[CastVoteId](256).toManaged_
      voteStream <- ZStream.fromQueue(voteQueue).groupedWithin(100, 300.millis).broadcastDynamic(128)
    } yield SlideAppLive(
      slideStateRef = slideVar.ref,
      slideStateStream = slideVar.changes,
      questionStateRef = questionsVar.ref,
      questionStateStream = questionsVar.changes,
      voteQueue = voteQueue,
      voteStream = voteStream,
      populationStatsRef = populationStatsVar.ref,
      populationStatsStream = populationStatsVar.changes
    )
  }.toLayer
}
