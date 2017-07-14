package org.byzantine.pos

import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.pattern.ask
import akka.util.Timeout

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.concurrent.Await
import scala.util.Random
import scala.collection.mutable

object Demo extends App {
  val system = ActorSystem("pos")
  val N: Int = 1000
  val numAssignedPeers = 3

  // Create nodes
  val nodesBuffer = new ListBuffer[Tuple2[Int, ActorRef]]()

  for (i <- 0 until N) {
    nodesBuffer.append((i, system.actorOf(Props(classOf[Node], i), i.toString)))
  }

  def nodes = nodesBuffer.map(el => el._2).toList
  // Inform them about each other
  for (initNode <- nodes) {
    val assignedPeers = Random.shuffle(nodes).take(numAssignedPeers)
    for (peerNode <- assignedPeers) {
      if (initNode != peerNode)
        initNode ! ConnectMsg(peerNode)
    }
  }

  import system.dispatcher
  def runnable(f: => Unit): Runnable = new Runnable() { def run() = f }

  // All nodes try to mint from time to time
  system.scheduler.schedule(0 seconds, 1 seconds, runnable {
    val nodesWithIds = nodesBuffer.toList
    for (node <- nodesWithIds) {
      node._2 ! Node.MintCmd(Address(node._1))
    }
  })

  val rand = new Random()
  def randomNodeID(): Int = rand.nextInt(N)

  var ok = true
  while (ok) {
    val ln = scala.io.StdIn.readLine()

    if (ln.contains('S')) {
      val shuffledNodesWithIds = scala.util.Random.shuffle(nodesBuffer.toList)
      val receiverID = shuffledNodesWithIds.last._1

      var sent = false
      for (sender <- shuffledNodesWithIds) {
        if (!sent) {
          val future = (sender._2).ask(Node.TransferCmd(Address(sender._1), Address(receiverID), 5))(5 seconds)
          sent = sent || Await.result(future, 5 seconds).asInstanceOf[Boolean]
        }
      }
    }
    else if(ln.contains('M')) {
      val mempools = new mutable.HashSet[Set[Tuple2[Hash, Transaction]]]()

      for (node <- nodes) {
        val future = node.ask(Node.MemPoolCmd)(5 seconds)
        val mp = Await.result(future, 5 seconds).asInstanceOf[Set[Tuple2[Hash, Transaction]]]
        mempools += mp
      }

      println(mempools.size + " different mempools in the network.")
      println(mempools)
    }

    else if (ln.contains('B')) {
      val blockchainHeads = new mutable.HashSet[Hash]()

      for (node <- nodes) {
        val future = node.ask(Node.BlockchainCmd)(5 seconds)
        val bc = Await.result(future, 5 seconds).asInstanceOf[Hash]
        blockchainHeads += bc
      }

      println(blockchainHeads.size + " different blockchains in the network.")
      println("Heads: " + blockchainHeads)
    }

    else if (ln.length > 0 && ln.head == 'K') {
      val id = ln.tail.toInt
      nodes(id) ! PoisonPill
      nodesBuffer -= Tuple2(id, nodes(id))
    }

    else if (ln.contains('P')) {
      for (node <- nodes) {
        node ! Node.ListPeersCmd
      }
    }

    ok = ln != ""
  }

  system.terminate()
}
