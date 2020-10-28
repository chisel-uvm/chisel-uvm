package heappriorityqueue

import chiseltest._
import org.scalatest._
import chisel3._
import lib._
import heappriorityqueue.helpers._

import scala.annotation.tailrec

object helpers {
  var cWid = 2
  var nWid = 8
  var rWid = 3

  def setWidths(c: Int, n: Int, r: Int) : Unit = {
    cWid = c
    nWid = n
    rWid = r
  }

  def pokePrioAndID(port: PriorityAndID, poke: Seq[Int] = null) : Seq[Int] = {
    if(poke != null){
      port.prio.cycl.poke(poke(0).U)
      port.prio.norm.poke(poke(1).U)
      port.id.poke(poke(2).U)
      return poke
    }else{
      val rand = scala.util.Random
      val poke = Seq(rand.nextInt(math.pow(2,cWid).toInt),rand.nextInt(math.pow(2,nWid).toInt),rand.nextInt(math.pow(2,rWid).toInt))
      pokePrioAndID(port, poke)
    }
  }

  def pokePrioAndIDVec(port: Vec[PriorityAndID], poke: Seq[Seq[Int]] = null) : Seq[Seq[Int]] = {
    if(poke != null) Seq.tabulate(port.length)(i => pokePrioAndID(port(i),poke(i)))
    else Seq.tabulate(port.length)(i => pokePrioAndID(port(i)))
  }

  def peekPrioAndId(port: PriorityAndID) : Seq[Int] = {
    Seq(port.prio.cycl, port.prio.norm, port.id).map(_.peek.litValue.toInt)
  }

  def peekPrioAndIdVec(port: Vec[PriorityAndID]) : Seq[Seq[Int]] = {
    Seq.tabulate(port.length)(i => peekPrioAndId(port(i)))
  }

  def prioAndIdToString(data: Seq[Int]) : String = {
    data.mkString(":")
  }

  def prioAndIdVecToString(data: Seq[Seq[Int]]) : String = {
    data.map(_.mkString(":")).mkString(", ")
  }
}

/**
 * Wrapper class to abstract interaction with the heap-based priority queue
 * @param dut a HeapPriorityQueue instance
 * @param size size of the heap
 * @param chCount number of children per node
 * @param debug 0=no output, 1=operation reviews, 2=step-wise outputs
 * @param cWid  width of cyclic priorities
 * @param nWid  width of normal priorities
 * @param rWid  width of reference IDs
 */
class HeapPriorityQueueWrapper(dut: HeapPriorityQueue, size: Int, chCount: Int, debug: Int)(cWid : Int, nWid : Int, rWid : Int){
  var pipedRdAddr = 0
  var pipedWrAddr = 0
  var searchSimDelay = 0
  var stepCounter = 0
  var totalSteps = 0
  var debugLvl = debug
  val states = Array("idle" ,"headInsertion", "normalInsertion", "initSearch", "waitForSearch", "resetCell", "lastRemoval", "headRemoval", "tailRemoval" ,"removal", "waitForHeapifyUp", "waitForHeapifyDown")

  var mem = Array.fill(size-1)(Array(Math.pow(2,cWid).toInt-1,Math.pow(2,nWid).toInt-1,Math.pow(2,rWid).toInt-1)).sliding(chCount,chCount).toArray
  dut.io.srch.done.poke(true.B)

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  def stepDut(n: Int) : String = {
    val str = new StringBuilder
    for(i <- 0 until n){
      // read port
      try {
        for (i <- 0 until chCount) {
          // ignores reads outside of array
          dut.io.rdPort.data(i).prio.cycl.poke(mem(pipedRdAddr)(i)(0).U)
          dut.io.rdPort.data(i).prio.norm.poke(mem(pipedRdAddr)(i)(1).U)
          dut.io.rdPort.data(i).id.poke(mem(pipedRdAddr)(i)(2).U)
        }
      } catch {
        case e: IndexOutOfBoundsException => {}
      }
      // write port
      if (dut.io.wrPort.write.peek.litToBoolean) {
        for (i <- 0 until chCount) {
          if((dut.io.wrPort.mask.peek.litValue & (1 << i)) != 0){
            mem(pipedWrAddr)(i)(0) = dut.io.wrPort.data(i).prio.cycl.peek.litValue.toInt
            mem(pipedWrAddr)(i)(1) = dut.io.wrPort.data(i).prio.norm.peek.litValue.toInt
            mem(pipedWrAddr)(i)(2) = dut.io.wrPort.data(i).id.peek.litValue.toInt
          }
        }
      }
      // search port
      if(dut.io.srch.search.peek.litToBoolean){
        if(searchSimDelay > 1){
          var idx = 0
          if(!(dut.io.head.refID.peek.litValue == dut.io.srch.refID.peek.litValue)){
            idx = mem.flatten.map(_(2)==dut.io.srch.refID.peek.litValue.toInt).indexOf(true)
            if(idx == -1){
              dut.io.srch.error.poke(true.B)
            }else{
              dut.io.srch.res.poke((idx+1).U)
            }
          }else{
            dut.io.srch.res.poke(0.U)
          }
          dut.io.srch.done.poke(true.B)
          searchSimDelay = 0
        }else{
          dut.io.srch.done.poke(false.B)
          dut.io.srch.error.poke(false.B)
          searchSimDelay += 1
        }
      }else{
        dut.io.srch.done.poke(true.B)
        searchSimDelay = 0
      }
      str.append(
        s"${states(dut.io.state.peek.litValue.toInt)} : ${dut.io.cmd.done.peek.litValue} : ${dut.io.cmd.result.peek.litValue}\n"+
          s"ReadPort: ${dut.io.rdPort.address.peek.litValue} | ${if(pipedRdAddr<size/chCount)mem(pipedRdAddr).map(_.mkString(":")).mkString(",") else ""}\n"+
          s"WritePort: ${dut.io.wrPort.address.peek.litValue} | ${prioAndIdVecToString(peekPrioAndIdVec(dut.io.wrPort.data))} | ${dut.io.wrPort.write.peek.litToBoolean} | ${dut.io.wrPort.mask.peek.litValue.toString(2).reverse}\n"+
          getMem()+s"\n${"-"*40}\n"
      )


      // simulate synchronous memory
      pipedRdAddr = dut.io.rdPort.address.peek.litValue.toInt
      pipedWrAddr = dut.io.wrPort.address.peek.litValue.toInt

      dut.clock.step(1)
      stepCounter += 1
      totalSteps += 1
    }
    return str.toString
  }

  def stepUntilDone() : String = {
    var iterations = 0
    val str = new StringBuilder
    str.append(stepDut(1))
    while(!dut.io.cmd.done.peek.litToBoolean){
      str.append(stepDut(1))
    }
    return str.toString
  }

  def pokeID(id: Int) : Unit = {
    dut.io.cmd.refID.poke(id.U)
  }

  def pokePriority(c: Int, n: Int) : Unit = {
    dut.io.cmd.prio.norm.poke(n.U)
    dut.io.cmd.prio.cycl.poke(c.U)
  }

  def pokePrioAndID(c: Int, n: Int, id: Int) : Unit = {
    pokePriority(c,n)
    pokeID(id)
  }

  def insert(c: Int, n: Int, id: Int) : (Int, Boolean, String) = {
    if(debugLvl >= 2) println(s"Inserting $c:$n:$id${"-"*20}")
    pokePrioAndID(c,n,id)
    dut.io.cmd.op.poke(true.B)
    dut.io.cmd.valid.poke(true.B)
    stepCounter = 0
    val debug = stepUntilDone()
    dut.io.cmd.valid.poke(false.B)
    if(debugLvl >= 1) println(s"Inserting $c:$n:$id ${if(!getSuccess())"failed" else "success"} in $stepCounter cycles")
    return (stepCounter,getSuccess(),debug)
  }

  def insert(arr: Array[Array[Int]]) : (Int, Boolean) = {
    var success = true
    var steps = 0
    for(i <- arr){
      val ret = insert(i(0),i(1),i(2))
      success &= ret._2
      steps += ret._1
    }
    return (steps, success)
  }

  def remove(id: Int) : (Int, Boolean, String) = {
    if(debugLvl >= 2) println(s"Removing $id${"-"*20}")
    pokeID(id)
    dut.io.cmd.op.poke(false.B)
    dut.io.cmd.valid.poke(true.B)
    stepCounter = 0
    val debug = stepUntilDone()
    dut.io.cmd.valid.poke(false.B)
    if(debugLvl >= 1) println(s"Remove ID=$id ${if(!getSuccess())"failed" else "success: "+getRmPrio().mkString(":")} in $stepCounter cycles")
    (stepCounter,getSuccess(),debug)
  }

  def printMem(style: Int = 0) : Unit = {
    if(style==0) println(s"DUT: ${dut.io.head.prio.peek.getElements.map(_.litValue.toInt).mkString(":")}:${dut.io.head.refID.peek.litValue} | ${mem.map(_.map(_.mkString(":")).mkString(", ")).mkString(" | ")}")
    else if(style==1) println(s"DUT:\n${dut.io.head.prio.peek.getElements.mkString(":")}:${dut.io.head.refID.peek.litValue}\n${mem.map(_.map(_.mkString(":")).mkString(", ")).mkString("\n")}")
  }

  def getMem() : String = {
    return s"${dut.io.head.prio.cycl.peek.litValue}:${dut.io.head.prio.norm.peek.litValue}:${dut.io.head.refID.peek.litValue} | ${mem.map(_.map(_.mkString(":")).mkString(", ")).mkString(" | ")}"
  }

  def getRmPrio() : Array[Int] = {
    return Seq(dut.io.cmd.rm_prio.cycl,dut.io.cmd.rm_prio.norm).map(_.peek.litValue.toInt).toArray
  }

  def getSuccess() : Boolean = {
    return !dut.io.cmd.result.peek.litToBoolean
  }

  def compareWithModel(arr: Array[Array[Int]]) : Boolean = {
    var res = true
    dut.io.head.prio.cycl.expect(arr(0)(0).U)
    res &= dut.io.head.prio.cycl.peek.litValue == arr(0)(0)
    dut.io.head.prio.norm.expect(arr(0)(1).U)
    res &= dut.io.head.prio.norm.peek.litValue == arr(0)(1)
    dut.io.head.refID.expect(arr(0)(2).U)
    res &= dut.io.head.refID.peek.litValue == arr(0)(2)
    res &= arr.slice(1,arr.length).deep == mem.flatten.deep
    return res
  }

  def setDebugLvl(lvl: Int) : Unit = {
    debugLvl = lvl
  }
}