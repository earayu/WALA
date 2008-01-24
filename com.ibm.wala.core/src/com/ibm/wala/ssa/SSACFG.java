/*******************************************************************************
 * Copyright (c) 2002 - 2006 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.wala.ssa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ibm.wala.cfg.AbstractCFG;
import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.cfg.IBasicBlock;
import com.ibm.wala.cfg.ShrikeCFG;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IClassLoader;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.shrikeBT.ExceptionHandler;
import com.ibm.wala.shrikeBT.IInstruction;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.Function;
import com.ibm.wala.util.ShrikeUtil;
import com.ibm.wala.util.collections.EmptyIterator;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.Iterator2Collection;
import com.ibm.wala.util.collections.MapIterator;
import com.ibm.wala.util.debug.Assertions;
import com.ibm.wala.util.debug.Trace;
import com.ibm.wala.util.debug.UnimplementedError;
import com.ibm.wala.util.graph.impl.NumberedNodeIterator;
import com.ibm.wala.util.intset.BitVector;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.warnings.Warning;
import com.ibm.wala.util.warnings.Warnings;

/**
 * A control-flow graph for ssa form.
 * 
 * @author sfink
 */

public class SSACFG implements ControlFlowGraph<ISSABasicBlock> {

  private static final boolean DEBUG = false;

  private BasicBlock[] basicBlocks;

  final protected SSAInstruction[] instructions;

  final protected IMethod method;

  final protected AbstractCFG<IBasicBlock> cfg;

  /**
   * cache a ref to the exit block for efficient access
   */
  private BasicBlock exit;

  protected SSACFG(SSACFG aCFG) {
    this.cfg = aCFG.cfg;
    this.method = aCFG.method;
    this.basicBlocks = aCFG.basicBlocks;
    this.instructions = aCFG.instructions;
  }

  /**
   * @throws IllegalArgumentException
   *             if method is null
   */
  @SuppressWarnings("unchecked")
  public SSACFG(IMethod method, AbstractCFG cfg, SSAInstruction[] instructions) {

    if (method == null) {
      throw new IllegalArgumentException("method is null");
    }
    this.cfg = cfg;
    if (DEBUG) {
      Trace.println("Incoming CFG for " + method + ":");
      Trace.println(cfg.toString());
    }

    this.method = method;
    if (Assertions.verifyAssertions) {
      if (method.getDeclaringClass() == null) {
        Assertions._assert(method.getDeclaringClass() != null, "null declaring class for " + method);
      }
    }
    createBasicBlocks(cfg);
    if (cfg instanceof ShrikeCFG) {
      recordExceptionTypes(((ShrikeCFG) cfg).getExceptionHandlers(), method.getDeclaringClass().getClassLoader());
    }
    this.instructions = instructions;

  }

  @Override
  public int hashCode() {
    return -3 * cfg.hashCode();
  }

  @Override
  public boolean equals(Object o) {
    return (o instanceof SSACFG) && cfg.equals(((SSACFG) o).cfg);
  }

  private void recordExceptionTypes(Set<ExceptionHandler> set, IClassLoader loader) {
    for (Iterator<ExceptionHandler> it = set.iterator(); it.hasNext();) {
      ExceptionHandler handler = it.next();
      TypeReference t = null;
      if (handler.getCatchClass() == null) {
        // by convention, in ShrikeCT this means catch everything
        t = TypeReference.JavaLangThrowable;
      } else {
        TypeReference exceptionType = ShrikeUtil.makeTypeReference(loader.getReference(), handler.getCatchClass());
        IClass klass = null;
        klass = loader.lookupClass(exceptionType.getName());
        if (klass == null) {
          Warnings.add(ExceptionLoadFailure.create(exceptionType, method));
          t = exceptionType;
        } else {
          t = klass.getReference();
        }
      }
      int instructionIndex = handler.getHandler();
      if (Assertions.verifyAssertions) {
        IBasicBlock b = getBlockForInstruction(instructionIndex);
        if (!(b instanceof ExceptionHandlerBasicBlock)) {
          Assertions._assert(b instanceof ExceptionHandlerBasicBlock, "not exception handler " + b + " index " + instructionIndex);
        }
      }
      ExceptionHandlerBasicBlock bb = (ExceptionHandlerBasicBlock) getBlockForInstruction(instructionIndex);
      bb.addCaughtExceptionType(t);
    }
  }

  private void createBasicBlocks(AbstractCFG G) {
    basicBlocks = new BasicBlock[G.getNumberOfNodes()];
    for (int i = 0; i <= G.getMaxNumber(); i++) {
      if (G.getCatchBlocks().get(i)) {
        basicBlocks[i] = new ExceptionHandlerBasicBlock(i);
      } else {
        basicBlocks[i] = new BasicBlock(i);
      }
    }
    exit = basicBlocks[cfg.getNumber(cfg.exit())];
  }

  /**
   * Get the basic block an instruction belongs to. Note: the instruction2Block
   * array is filled in lazily. During initialization, the mapping is set up
   * only for the first instruction of each basic block.
   */
  public SSACFG.BasicBlock getBlockForInstruction(int instructionIndex) {
    IBasicBlock N = cfg.getBlockForInstruction(instructionIndex);
    int number = cfg.getNumber(N);
    return basicBlocks[number];
  }

  /**
   * NB: Use iterators such as IR.iterateAllInstructions() instead of this
   * method. This will probably be deprecated someday.
   * 
   * Return the instructions. Note that the CFG is created from the Shrike CFG
   * prior to creating the SSA instructions.
   * 
   * @return an array containing the SSA instructions.
   */
  public SSAInstruction[] getInstructions() {
    return instructions;
  }

  private final Map<RefPathKey, SSAPiInstruction> piInstructions = HashMapFactory.make(2);

  private class RefPathKey {
    private final int n;

    private final Object src;

    private final Object path;

    RefPathKey(int n, Object src, Object path) {
      this.n = n;
      this.src = src;
      this.path = path;
    }

    @Override
    public int hashCode() {
      return n * path.hashCode();
    }

    @Override
    public boolean equals(Object x) {
      return (x instanceof RefPathKey) && n == ((RefPathKey) x).n && src == ((RefPathKey) x).src && path == ((RefPathKey) x).path;
    }
  }

  /**
   * A Basic Block in an SSA IR
   * 
   */
  public class BasicBlock implements ISSABasicBlock {

    /**
     * state needed for the numbered graph.
     */
    private final int number;

    /**
     * List of PhiInstructions associated with the entry of this block.
     */
    private SSAPhiInstruction stackSlotPhis[];

    private SSAPhiInstruction localPhis[];

    private final static int initialCapacity = 10;

    public BasicBlock(int number) {
      this.number = number;
    }

    public int getNumber() {
      return number;
    }

    /**
     * Method getFirstInstructionIndex.
     */
    public int getFirstInstructionIndex() {
      IBasicBlock B = cfg.getNode(number);
      return B.getFirstInstructionIndex();
    }

    /**
     * Is this block marked as a catch block?
     */
    public boolean isCatchBlock() {
      return cfg.getCatchBlocks().get(getNumber());
    }

    public int getLastInstructionIndex() {
      IBasicBlock B = cfg.getNode(number);
      return B.getLastInstructionIndex();
    }

    /*
     * @see com.ibm.wala.ssa.ISSABasicBlock#iteratePhis()
     */
    public Iterator<SSAPhiInstruction> iteratePhis() {
      compressPhis();
      if (stackSlotPhis == null) {
        if (localPhis == null) {
          return EmptyIterator.instance();
        } else {
          LinkedList<SSAPhiInstruction> result = new LinkedList<SSAPhiInstruction>();
          for (SSAPhiInstruction phi : localPhis) {
            if (phi != null) {
              result.add(phi);
            }
          }
          return result.iterator();
        }
      } else {
        // stackSlotPhis != null
        LinkedList<SSAPhiInstruction> result = new LinkedList<SSAPhiInstruction>();
        for (SSAPhiInstruction phi : stackSlotPhis) {
          if (phi != null) {
            result.add(phi);
          }
        }
        if (localPhis == null) {
          return result.iterator();
        } else {
          for (SSAPhiInstruction phi : localPhis) {
            if (phi != null) {
              result.add(phi);
            }
          }
          return result.iterator();
        }
      }
    }

    /**
     * This method is used during SSA construction.
     */
    public SSAPhiInstruction getPhiForStackSlot(int slot) {
      if (stackSlotPhis == null) {
        return null;
      } else {
        if (slot >= stackSlotPhis.length) {
          return null;
        } else {
          return stackSlotPhis[slot];
        }
      }
    }

    /**
     * This method is used during SSA construction.
     */
    public SSAPhiInstruction getPhiForLocal(int n) {
      if (localPhis == null) {
        return null;
      } else {
        if (n >= localPhis.length) {
          return null;
        } else {
          return localPhis[n];
        }
      }
    }

    public void addPhiForStackSlot(int slot, SSAPhiInstruction phi) {
      if (stackSlotPhis == null) {
        stackSlotPhis = new SSAPhiInstruction[initialCapacity];
      }
      if (slot >= stackSlotPhis.length) {
        SSAPhiInstruction[] temp = stackSlotPhis;
        stackSlotPhis = new SSAPhiInstruction[slot * 2];
        System.arraycopy(temp, 0, stackSlotPhis, 0, temp.length);
      }
      stackSlotPhis[slot] = phi;
    }

    public void addPhiForLocal(int n, SSAPhiInstruction phi) {
      if (localPhis == null) {
        localPhis = new SSAPhiInstruction[initialCapacity];
      }
      if (n >= localPhis.length) {
        SSAPhiInstruction[] temp = localPhis;
        localPhis = new SSAPhiInstruction[n * 2];
        System.arraycopy(temp, 0, localPhis, 0, temp.length);
      }
      localPhis[n] = phi;
    }

    /**
     * Remove any phis in the set.
     */
    public void removePhis(Set<SSAPhiInstruction> toRemove) {
      int nRemoved = 0;
      if (stackSlotPhis != null) {
        for (int i = 0; i < stackSlotPhis.length; i++) {
          if (toRemove.contains(stackSlotPhis[i])) {
            stackSlotPhis[i] = null;
            nRemoved++;
          }
        }
      }
      if (nRemoved > 0) {
        int newLength = stackSlotPhis.length - nRemoved;
        if (newLength == 0) {
          stackSlotPhis = null;
        } else {
          SSAPhiInstruction[] old = stackSlotPhis;
          stackSlotPhis = new SSAPhiInstruction[newLength];
          int j = 0;
          for (int i = 0; i < old.length; i++) {
            if (old[i] != null) {
              stackSlotPhis[j++] = old[i];
            }
          }
        }
      }
      nRemoved = 0;
      if (localPhis != null) {
        for (int i = 0; i < localPhis.length; i++) {
          if (toRemove.contains(localPhis[i])) {
            localPhis[i] = null;
            nRemoved++;
          }
        }
      }
      if (nRemoved > 0) {
        int newLength = localPhis.length - nRemoved;
        if (newLength == 0) {
          localPhis = null;
        } else {
          SSAPhiInstruction[] old = localPhis;
          localPhis = new SSAPhiInstruction[newLength];
          int j = 0;
          for (int i = 0; i < old.length; i++) {
            if (old[i] != null) {
              localPhis[j++] = old[i];
            }
          }
        }
      }
    }

    SSAPiInstruction getPiForRefAndPath(int n, Object path) {
      return piInstructions.get(new RefPathKey(n, this, path));
    }

    private final LinkedList<SSAPiInstruction> blockPiInstructions = new LinkedList<SSAPiInstruction>();

    void addPiForRefAndPath(int n, Object path, SSAPiInstruction pi) {
      piInstructions.put(new RefPathKey(n, this, path), pi);
      blockPiInstructions.add(pi);
    }

    public Iterator<SSAPiInstruction> iteratePis() {
      return blockPiInstructions.iterator();
    }

    public Iterator<SSAInstruction> iterateNormalInstructions() {
      int lookup = getFirstInstructionIndex();
      final int end = getLastInstructionIndex();
      // skip to first non-null instruction
      while (lookup <= end && instructions[lookup] == null) {
        lookup++;
      }
      final int dummy = lookup;
      return new Iterator<SSAInstruction>() {
        private int start = dummy;

        public boolean hasNext() {
          return (start <= end);
        }

        public SSAInstruction next() {
          SSAInstruction i = instructions[start];
          start++;
          while (start <= end && instructions[start] == null) {
            start++;
          }
          return i;
        }

        public void remove() {
          throw new UnsupportedOperationException();
        }
      };
    }

    /**
     * TODO: make this more efficient if needed
     */
    public List<SSAInstruction> getAllInstructions() {
      compressPhis();

      ArrayList<SSAInstruction> result = new ArrayList<SSAInstruction>();
      for (Iterator<? extends SSAInstruction> it = iteratePhis(); it.hasNext();) {
        result.add(it.next());
      }

      for (int i = getFirstInstructionIndex(); i <= getLastInstructionIndex(); i++) {
        SSAInstruction s = instructions[i];
        if (s != null) {
          result.add(s);
        }
      }

      for (Iterator<? extends SSAInstruction> it = iteratePis(); it.hasNext();) {
        result.add(it.next());
      }
      return result;
    }

    /**
     * rewrite the phi arrays so they have no null entries.
     */
    private void compressPhis() {
      if (stackSlotPhis != null && stackSlotPhis[stackSlotPhis.length - 1] == null) {
        int size = countNonNull(stackSlotPhis);
        if (size == 0) {
          stackSlotPhis = null;
        } else {
          SSAPhiInstruction[] old = stackSlotPhis;
          stackSlotPhis = new SSAPhiInstruction[size];
          int j = 0;
          for (int i = 0; i < old.length; i++) {
            if (old[i] != null) {
              stackSlotPhis[j++] = old[i];
            }
          }
        }
      }
      if (localPhis != null && localPhis[localPhis.length - 1] == null) {
        int size = countNonNull(localPhis);
        if (size == 0) {
          localPhis = null;
        } else {
          SSAPhiInstruction[] old = localPhis;
          localPhis = new SSAPhiInstruction[size];
          int j = 0;
          for (int i = 0; i < old.length; i++) {
            if (old[i] != null) {
              localPhis[j++] = old[i];
            }
          }
        }
      }
    }

    private int countNonNull(SSAPhiInstruction[] a) {
      int result = 0;
      for (int i = 0; i < a.length; i++) {
        if (a[i] != null) {
          result++;
        }
      }
      return result;
    }

    public Iterator<IInstruction> iterator() {
      Function<SSAInstruction, IInstruction> kludge = new Function<SSAInstruction, IInstruction>() {
        public IInstruction apply(SSAInstruction s) {
          return s;
        }
      };
      return new MapIterator<SSAInstruction, IInstruction>(getAllInstructions().iterator(), kludge);
    }

    /**
     * @return true iff this basic block has at least one phi
     */
    public boolean hasPhi() {
      return stackSlotPhis != null || localPhis != null;
    }

    /*
     * @see com.ibm.wala.util.graph.INodeWithNumber#getGraphNodeId()
     */
    public int getGraphNodeId() {
      return number;
    }

    /*
     * @see com.ibm.wala.util.graph.INodeWithNumber#setGraphNodeId(int)
     */
    public void setGraphNodeId(int number) {
      // TODO Auto-generated method stub
    }

    /**
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
      return "BB[SSA:" + getFirstInstructionIndex() + ".." + getLastInstructionIndex() + "]" + getNumber() + " - "
          + method.getSignature();
    }

    private SSACFG getGraph() {
      return SSACFG.this;
    }

    @Override
    public boolean equals(Object arg0) {
      if (arg0 instanceof BasicBlock) {
        BasicBlock b = (BasicBlock) arg0;
        if (getNumber() == b.getNumber()) {
          if (getMethod().equals(b.getMethod())) {
            return getGraph().equals(b.getGraph());
          } else {
            return false;
          }
        } else {
          return false;
        }
      } else {
        return false;
      }
    }

    public IMethod getMethod() {
      return method;
    }

    @Override
    public int hashCode() {
      return cfg.getNode(getNumber()).hashCode() * 6271;
    }

    /*
     * @see com.ibm.wala.cfg.IBasicBlock#isExitBlock()
     */
    public boolean isExitBlock() {
      return this == SSACFG.this.exit();
    }

    /*
     * @see com.ibm.wala.cfg.IBasicBlock#isEntryBlock()
     */
    public boolean isEntryBlock() {
      return this == SSACFG.this.entry();
    }

    public SSAInstruction getLastInstruction() {
      return instructions[getLastInstructionIndex()];
    }

    /**
     * The {@link ExceptionHandlerBasicBlock} subclass will override this.
     * 
     * @see com.ibm.wala.ssa.ISSABasicBlock#getCaughtExceptionTypes()
     */
    public Iterator<TypeReference> getCaughtExceptionTypes() {
      return EmptyIterator.instance();
    }
  }

  public class ExceptionHandlerBasicBlock extends BasicBlock {

    /**
     * The type of the exception caught by this block.
     */
    private TypeReference[] exceptionTypes;

    private final static int initialCapacity = 3;

    private int nExceptionTypes = 0;

    /**
     * Instruction that defines the exception value this block catches
     */
    private SSAGetCaughtExceptionInstruction catchInstruction;

    public ExceptionHandlerBasicBlock(int number) {
      super(number);
    }

    public SSAGetCaughtExceptionInstruction getCatchInstruction() {
      return catchInstruction;
    }

    public void setCatchInstruction(SSAGetCaughtExceptionInstruction catchInstruction) {
      this.catchInstruction = catchInstruction;
    }

    @Override
    public Iterator<TypeReference> getCaughtExceptionTypes() {
      return new Iterator<TypeReference>() {
        int next = 0;

        public boolean hasNext() {
          return next < nExceptionTypes;
        }

        public TypeReference next() {
          return exceptionTypes[next++];
        }

        public void remove() {
          Assertions.UNREACHABLE();
        }
      };
    }

    @Override
    public String toString() {
      return "BB(Handler)[SSA]" + getNumber() + " - " + method.getSignature();
    }

    public void addCaughtExceptionType(TypeReference exceptionType) {
      if (exceptionTypes == null) {
        exceptionTypes = new TypeReference[initialCapacity];
      }
      nExceptionTypes++;
      if (nExceptionTypes > exceptionTypes.length) {
        TypeReference[] temp = exceptionTypes;
        exceptionTypes = new TypeReference[nExceptionTypes * 2];
        System.arraycopy(temp, 0, exceptionTypes, 0, temp.length);
      }
      exceptionTypes[nExceptionTypes - 1] = exceptionType;
    }

    @Override
    public List<SSAInstruction> getAllInstructions() {
      List<SSAInstruction> result = super.getAllInstructions();
      if (catchInstruction != null) {
        // it's disturbing that catchInstruction can be null here. must be some
        // strange corner case
        // involving dead code. oh well .. keep on chugging until we have hard
        // evidence that this is a bug
	result.add(0, catchInstruction);
      }
      return result;
    }

  }

  @Override
  public String toString() {
    StringBuffer s = new StringBuffer("");
    for (int i = 0; i <= getNumber(exit()); i++) {
      BasicBlock bb = getNode(i);
      s.append("BB").append(i).append("[").append(bb.getFirstInstructionIndex()).append("..").append(bb.getLastInstructionIndex())
          .append("]\n");

      Iterator succNodes = getSuccNodes(bb);
      while (succNodes.hasNext()) {
        s.append("    -> BB").append(((BasicBlock) succNodes.next()).getNumber()).append("\n");
      }
    }
    return s.toString();
  }

  public BitVector getCatchBlocks() {
    return cfg.getCatchBlocks();
  }

  /**
   * is the given i a catch block?
   * 
   * @return true if catch block, false otherwise
   */
  public boolean isCatchBlock(int i) {
    return cfg.isCatchBlock(i);
  }

  /*
   * @see com.ibm.wala.cfg.ControlFlowGraph#entry()
   */
  public SSACFG.BasicBlock entry() {
    return basicBlocks[0];
  }

  /*
   * @see com.ibm.wala.cfg.ControlFlowGraph#exit()
   */
  public SSACFG.BasicBlock exit() {
    return exit;
  }

  /*
   * @see com.ibm.wala.util.graph.NumberedGraph#getNumber(com.ibm.wala.util.graph.Node)
   */
  public int getNumber(ISSABasicBlock b) throws IllegalArgumentException {
    if (b == null) {
      throw new IllegalArgumentException("N == null");
    }
    return b.getNumber();
  }

  /*
   * @see com.ibm.wala.util.graph.NumberedGraph#getNode(int)
   */
  public BasicBlock getNode(int number) {
    return basicBlocks[number];
  }

  /*
   * @see com.ibm.wala.util.graph.NumberedGraph#getMaxNumber()
   */
  public int getMaxNumber() {
    return basicBlocks.length - 1;
  }

  /*
   * @see com.ibm.wala.util.graph.Graph#iterateNodes()
   */
  public Iterator<ISSABasicBlock> iterator() {
    ArrayList<ISSABasicBlock> list = new ArrayList<ISSABasicBlock>();
    for (BasicBlock b : basicBlocks) {
      list.add(b);
    }
    return list.iterator();
  }

  /*
   * @see com.ibm.wala.util.graph.Graph#getNumberOfNodes()
   */
  public int getNumberOfNodes() {
    return cfg.getNumberOfNodes();
  }

  /*
   * @see com.ibm.wala.util.graph.Graph#getPredNodes(com.ibm.wala.util.graph.Node)
   */
  public Iterator<ISSABasicBlock> getPredNodes(ISSABasicBlock b) throws IllegalArgumentException {
    if (b == null) {
      throw new IllegalArgumentException("b == null");
    }
    IBasicBlock n = cfg.getNode(b.getNumber());
    final Iterator i = cfg.getPredNodes(n);
    return new Iterator<ISSABasicBlock>() {
      public boolean hasNext() {
        return i.hasNext();
      }

      public BasicBlock next() {
        IBasicBlock n = (IBasicBlock) i.next();
        int number = n.getNumber();
        return basicBlocks[number];
      }

      public void remove() {
        Assertions.UNREACHABLE();
      }
    };
  }

  /*
   * @see com.ibm.wala.util.graph.Graph#getPredNodeCount(com.ibm.wala.util.graph.Node)
   */
  public int getPredNodeCount(ISSABasicBlock b) throws IllegalArgumentException {
    if (b == null) {
      throw new IllegalArgumentException("b == null");
    }
    IBasicBlock n = cfg.getNode(b.getNumber());
    return cfg.getPredNodeCount(n);
  }

  /*
   * @see com.ibm.wala.util.graph.Graph#getSuccNodes(com.ibm.wala.util.graph.Node)
   */
  public Iterator<ISSABasicBlock> getSuccNodes(ISSABasicBlock b) throws IllegalArgumentException {
    if (b == null) {
      throw new IllegalArgumentException("b == null");
    }
    IBasicBlock n = cfg.getNode(b.getNumber());
    final Iterator i = cfg.getSuccNodes(n);
    return new Iterator<ISSABasicBlock>() {
      public boolean hasNext() {
        return i.hasNext();
      }

      public ISSABasicBlock next() {
        IBasicBlock n = (IBasicBlock) i.next();
        int number = n.getNumber();
        return basicBlocks[number];
      }

      public void remove() {
        Assertions.UNREACHABLE();
      }
    };
  }

  /*
   * @see com.ibm.wala.util.graph.Graph#getSuccNodeCount(com.ibm.wala.util.graph.Node)
   */
  public int getSuccNodeCount(ISSABasicBlock b) throws IllegalArgumentException {
    if (b == null) {
      throw new IllegalArgumentException("b == null");
    }
    IBasicBlock n = cfg.getNode(b.getNumber());
    return cfg.getSuccNodeCount(n);
  }

  /*
   * @see com.ibm.wala.util.graph.NumberedGraph#addNode(com.ibm.wala.util.graph.Node)
   */
  public void addNode(ISSABasicBlock n) throws UnsupportedOperationException {
    throw new UnsupportedOperationException();
  }

  public void addEdge(ISSABasicBlock src, ISSABasicBlock dst) throws UnsupportedOperationException {
    throw new UnsupportedOperationException();
  }

  public void removeEdge(ISSABasicBlock src, ISSABasicBlock dst) throws UnsupportedOperationException {
    throw new UnsupportedOperationException();
  }

  /*
   * @see com.ibm.wala.util.graph.EdgeManager#removeEdges(com.ibm.wala.util.graph.Node)
   */
  public void removeAllIncidentEdges(ISSABasicBlock node) throws UnsupportedOperationException {
    throw new UnsupportedOperationException();
  }

  /*
   * @see com.ibm.wala.util.graph.Graph#removeNode(com.ibm.wala.util.graph.Node)
   */
  public void removeNodeAndEdges(ISSABasicBlock N) throws UnsupportedOperationException {
    throw new UnsupportedOperationException();
  }

  /*
   * @see com.ibm.wala.util.graph.NodeManager#remove(com.ibm.wala.util.graph.Node)
   */
  public void removeNode(ISSABasicBlock n) throws UnsupportedOperationException {
    throw new UnsupportedOperationException();
  }

  /*
   * @see com.ibm.wala.cfg.ControlFlowGraph#getProgramCounter(int)
   */
  public int getProgramCounter(int index) {
    // delegate to incoming cfg.
    return cfg.getProgramCounter(index);
  }

  /*
   * @see com.ibm.wala.util.graph.Graph#containsNode(com.ibm.wala.util.graph.Node)
   */
  public boolean containsNode(ISSABasicBlock N) {
    if (N instanceof BasicBlock) {
      return basicBlocks[getNumber(N)] == N;
    } else {
      return false;
    }
  }

  /*
   * @see com.ibm.wala.cfg.ControlFlowGraph#getMethod()
   */
  public IMethod getMethod() {
    return method;
  }

  /*
   * @see com.ibm.wala.cfg.ControlFlowGraph#getExceptionalSuccessors(com.ibm.wala.cfg.IBasicBlock)
   */
  public List<ISSABasicBlock> getExceptionalSuccessors(final ISSABasicBlock b) {
    if (b == null) {
      throw new IllegalArgumentException("b is null");
    }
    final IBasicBlock n = cfg.getNode(b.getNumber());
    final Iterator i = cfg.getExceptionalSuccessors(n).iterator();
    final List<ISSABasicBlock> c = new ArrayList<ISSABasicBlock>(getSuccNodeCount(b));
    for (; i.hasNext();) {
      final IBasicBlock s = (IBasicBlock) i.next();
      c.add(basicBlocks[cfg.getNumber(s)]);
    }
    return c;
  }

  /*
   * @see com.ibm.wala.cfg.ControlFlowGraph#getExceptionalSuccessors(com.ibm.wala.cfg.IBasicBlock)
   */
  public Collection<ISSABasicBlock> getExceptionalPredecessors(ISSABasicBlock b) {
    if (b == null) {
      throw new IllegalArgumentException("b is null");
    }
    IBasicBlock n = cfg.getNode(b.getNumber());
    Function<IBasicBlock, ISSABasicBlock> f = new Function<IBasicBlock, ISSABasicBlock>() {
      public ISSABasicBlock apply(IBasicBlock object) {
        return basicBlocks[cfg.getNumber(object)];
      }
    };
    return Iterator2Collection.toCollection(new MapIterator<IBasicBlock, ISSABasicBlock>(cfg.getExceptionalPredecessors(n).iterator(),
        f));
  }

  /**
   * has exceptional edge src -> dest
   * 
   * @throws IllegalArgumentException
   *             if dest is null
   */
  public boolean hasExceptionalEdge(BasicBlock src, BasicBlock dest) {
    if (dest == null) {
      throw new IllegalArgumentException("dest is null");
    }
    if (dest.isExitBlock()) {
      int srcNum = getNumber(src);
      return cfg.getExceptionalToExit().get(srcNum);
    }
    // this is horrible and fragile
    return cfg.hasExceptionalEdge(src, dest);
  }

  /**
   * has normal edge src -> dest
   * 
   * @throws IllegalArgumentException
   *             if dest is null
   */
  public boolean hasNormalEdge(BasicBlock src, BasicBlock dest) {
    if (dest == null) {
      throw new IllegalArgumentException("dest is null");
    }
    if (dest.isExitBlock()) {
      int srcNum = getNumber(src);
      return cfg.getNormalToExit().get(srcNum);
    }
    // this is horrible and fragile.
    return cfg.hasNormalEdge(src, dest);
  }

  /*
   * @see com.ibm.wala.cfg.ControlFlowGraph#getNormalSuccessors(com.ibm.wala.cfg.IBasicBlock)
   */
  public Collection<ISSABasicBlock> getNormalSuccessors(ISSABasicBlock b) {
    if (b == null) {
      throw new IllegalArgumentException("b is null");
    }
    IBasicBlock n = cfg.getNode(b.getNumber());
    final Iterator i = cfg.getNormalSuccessors(n).iterator();
    Collection<ISSABasicBlock> c = new ArrayList<ISSABasicBlock>(getSuccNodeCount(b));
    for (; i.hasNext();) {
      IBasicBlock s = (IBasicBlock) i.next();
      c.add(basicBlocks[cfg.getNumber(s)]);
    }
    return c;
  }

  /*
   * @see com.ibm.wala.cfg.ControlFlowGraph#getNormalSuccessors(com.ibm.wala.cfg.IBasicBlock)
   */
  public Collection<ISSABasicBlock> getNormalPredecessors(ISSABasicBlock b) {
    if (b == null) {
      throw new IllegalArgumentException("b is null");
    }
    IBasicBlock n = cfg.getNode(b.getNumber());
    final Iterator i = cfg.getNormalPredecessors(n).iterator();
    Collection<ISSABasicBlock> c = new ArrayList<ISSABasicBlock>(getPredNodeCount(b));
    for (; i.hasNext();) {
      IBasicBlock s = (IBasicBlock) i.next();
      c.add(basicBlocks[cfg.getNumber(s)]);
    }
    return c;
  }

  /*
   * @see com.ibm.wala.util.graph.NumberedNodeManager#iterateNodes(com.ibm.wala.util.intset.IntSet)
   */
  public Iterator<ISSABasicBlock> iterateNodes(IntSet s) {
    return new NumberedNodeIterator<ISSABasicBlock>(s, this);
  }

  public void removeIncomingEdges(ISSABasicBlock node) throws UnsupportedOperationException {
    throw new UnsupportedOperationException();
  }

  public void removeOutgoingEdges(ISSABasicBlock node) throws UnsupportedOperationException {
    throw new UnsupportedOperationException();
  }

  public boolean hasEdge(ISSABasicBlock src, ISSABasicBlock dst) throws UnimplementedError {
    return getSuccNodeNumbers(src).contains(getNumber(dst));
  }

  public IntSet getSuccNodeNumbers(ISSABasicBlock b) throws IllegalArgumentException {
    if (b == null) {
      throw new IllegalArgumentException("b == null");
    }
    IBasicBlock n = cfg.getNode(b.getNumber());
    return cfg.getSuccNodeNumbers(n);
  }

  public IntSet getPredNodeNumbers(ISSABasicBlock node) throws UnimplementedError {
    Assertions.UNREACHABLE();
    return null;
  }

  /**
   * A warning for when we fail to resolve the type for a checkcast
   */
  private static class ExceptionLoadFailure extends Warning {

    final TypeReference type;

    final IMethod method;

    ExceptionLoadFailure(TypeReference type, IMethod method) {
      super(Warning.MODERATE);
      this.type = type;
      this.method = method;
    }

    @Override
    public String getMsg() {
      return getClass().toString() + " : " + type + " " + method;
    }

    public static ExceptionLoadFailure create(TypeReference type, IMethod method) {
      return new ExceptionLoadFailure(type, method);
    }
  }

  /**
   * @return the basic block with a particular number
   */
  public BasicBlock getBasicBlock(int bb) {
    return basicBlocks[bb];
  }

}
