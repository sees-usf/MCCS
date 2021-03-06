package  stategraph;

import java.util.*;

import   BinaryTree.BinaryTree;
import   common.DualHashMap;
import   common.IndexObjMap;
import   lpn.*;
import   util.*;

public class ProcSg extends StateGraph {
	//* Initial process state
	ProcState initPst;
	
	public static ProcState badPst = new ProcState(-1, null);
	
	//* Cache: local states with unique consecutive indices starting from 0 
    protected IndexObjMap<ProcState> pStateTbl;

    //* cache storing visible state transitions
    protected HashMap<ProcStateTran, ProcStateTran> uniquePsTranTbl;
    
    
	public ProcSg(LPN model) {
		this.lpn = model;
		this.pStateTbl = new IndexObjMap<ProcState>();
		this.uniquePsTranTbl = new HashMap<ProcStateTran, ProcStateTran>();
		this.initialize();
	}
	
	public ProcState setInitialState(ProcState init) {
		init = this.addState(init);
		this.initPst = init;
		return init;
	}
	
	public ProcState getInitialPst() {
		return this.initPst;
	}
	
	public ProcState addState(ProcState pState) {
		State cachedlst = super.addState(pState.getLocalState());
		return this.pStateTbl.add(new ProcState(pState.getGlobalVecIdx(), cachedlst));
	}

	public ProcState getState(ProcState pst) {
		return this.pStateTbl.get(pst);
	}
	
	public ProcStateTran getPsTran(ProcState cur, LPNTran lpntran, ProcState nxt) {
		if (this.pStateTbl.contains(cur) == false || this.pStateTbl.contains(nxt) == false)
			throw new IllegalStateException("states are not defined for the process");
		
		ProcStateTran tmp = new ProcStateTran(cur, lpntran, nxt);
		if (this.uniquePsTranTbl.containsKey(tmp) == true)
			return this.uniquePsTranTbl.get(tmp);
		
		this.uniquePsTranTbl.put(tmp, tmp);
		return tmp;
	}
	
	public boolean addExtTran(ProcState curSt, ProcStateTran extTran)  {//ProcState curSt, LPNTran lpnTran, int nxtGidx) {
		ProcState curpst = this.pStateTbl.get(curSt);
		return curpst.addExtTran(extTran);
	}
	
	public ProcState getSucc(ProcState pst, ProcStateTran extTran) {
		pst = this.pStateTbl.get(pst);
		
		ProcState succ = pst.getSucc(extTran);
		if (succ == null)
			return null;
		
		return this.addState(succ);
	}
	
	public boolean contains(ProcState pst) {
		return this.pStateTbl.contains(pst);
	}
	
	LinkedList<Pair<LPNTran, ProcState>> emptyEnabledList = new LinkedList<Pair<LPNTran, ProcState>>();
	public LinkedList<Pair<LPNTran, ProcState>> getEnabled(ProcState curPst, BinaryTree gVecTable, DualHashMap<String, Integer> gVarIndexMap) {
		if (curPst == null) {
			throw new NullPointerException();
		}
		
		//* First, check if current curPst exists. If yes, return the previously found 
		//* enabled transitions and next states.
		if(this.pStateTbl.contains(curPst)==true) {
			LinkedList<Pair<LPNTran, ProcState>> enabled = curPst.getEnabledTrans();
			if(enabled != null)
				return enabled;
		}
		else 
			throw new IllegalStateException("curPst does not exist in this ProcSg");
		
		
		LinkedList<Pair<LPNTran, ProcState>> nextStateList = curPst.getNextStateSet();
		if(nextStateList != null) {
			return nextStateList;
		}
		
		State curState = curPst.getLocalState();
		int curGidx = curPst.getGlobalVecIdx();
		
		//* Otherwise, find the enabled transitions and the next states for the new ProcState. 
		int[] marking = curState.getMarking();
		int[] vector = curState.getVector();

		int[] curVecCopy = Arrays.copyOf(vector, vector.length);
		DualHashMap<String, Integer> VarIdxMap = this.lpn.getVarIndexMap();
		// VarSet inputs = this.lpn.getInputs();
		// VarSet outputs = this.lpn.getOutputs();
		VarSet globals = this.lpn.getGlobals();

		// for(String var : inputs) {
		// int idx = VarIdxMap.getValue(var);
		// curVecCopy[idx] = PrjVec[VarIndexMap.getValue(var)];
		// }
		// for(String var : outputs) {
		// int idx = VarIdxMap.getValue(var);
		// curVecCopy[idx] = PrjVec[VarIndexMap.getValue(var)];
		// }

		int[] gVec = gVecTable.toIntArray(curGidx);

		for (String var : globals) {
			int idx = VarIdxMap.getValue(var);
			//System.out.println(var + "  "  + idx + "  " + gVarIndexMap.getValue(var));
			curVecCopy[idx] = gVec[gVarIndexMap.getValue(var)];
		}

		State curStateCopy = new State(this.lpn, marking, curVecCopy);

		// System.out.println("--> Check enabled trans of '" +
		// this.lpn.getLabel() + "' in state");
		// System.out.println(curStateCopy);

		LpnTranList PlacePostset = new LpnTranList();
		for (int place : marking) {
			LpnTranList tmp = this.Place2TranTbl.get(place);
			if (tmp == null)
				continue;

			for (LPNTran tran : tmp)
				PlacePostset.addLast(tran);
		}

		LpnTranList curEnabled = new LpnTranList();
		for (LPNTran tran : PlacePostset) {
			boolean enabled = true;
			int[] preset = tran.getPreSet();
			for (int prep : preset) {
				boolean marked = false;
				for (int m : marking) {
					if (prep == m) {
						marked = true;
						break;
					}
				}
				if (marked == false) {
					enabled = false;
					break;
				}
			}

			if (enabled == false)
				continue;

			if (tran.checkGuard(curStateCopy) == true) {
				if (tran.local() == true)
					curEnabled.addLast(tran);
				else
					curEnabled.addFirst(tran);
			}
		}

		//* Add the state transition due to firedTran into the corresponding SG. */		
		nextStateList = new LinkedList<Pair<LPNTran, ProcState>>();
		for(LPNTran tran : curEnabled) {
			ProcState tmp = tran.fire(gVec, curState, this, gVecTable, gVarIndexMap);
			ProcState nxtPst = this.pStateTbl.add(tmp);
			if (tran.local() == true)
				nextStateList.addLast(new Pair<LPNTran, ProcState>(tran, nxtPst));
			else
				nextStateList.addFirst(new Pair<LPNTran, ProcState>(tran, nxtPst));
		}
		if(nextStateList.size() > 0)
			curPst.setNextStateSet(nextStateList);
		else
			curPst.setNextStateSet(emptyEnabledList);

		return curPst.getEnabledTrans();
	}
	
	//* Find all reachable state from root.
	public HashSet<ProcState> findReachable(ProcState root) {
		root = this.addState(root);
		Stack<ProcState> pstStack = new Stack<ProcState>();
		pstStack.push(root);
		HashSet<ProcState> reachableSet = new HashSet<ProcState>();

		while(pstStack.empty()==false) {
			ProcState top = pstStack.pop();
			reachableSet.add(top);
			
			LinkedList<Pair<LPNTran, ProcState>> enabledlist = top.getEnabledTrans();
			for(Pair<LPNTran, ProcState> tran2nxt : enabledlist) {
				ProcState succ = tran2nxt.getRight();
				if(reachableSet.contains(succ) == false)
					pstStack.push(succ);
			}
		}
		return reachableSet;
	}
	
	public HashSet<ProcStateTran> getExtTrans(ProcState curSt) {
		ProcState curpst = this.pStateTbl.get(curSt);
		if (curpst == null)	return null;
		return curpst.getExtTrans();
	}
	
	public boolean containsExtTran(ProcState curpst, ProcStateTran extTran) {
		ProcState curpst_tmp = this.pStateTbl.get(curpst);
		if (curpst_tmp == null)	return false;
		return curpst_tmp.containsExtTran(extTran);
	}
	
	
	public int stateCount() {
		return this.pStateTbl.size();
	}
	
	public int tranCount() {
		int count = 0;
		for(int i = 0; i < this.pStateTbl.size(); i++)
			count += this.pStateTbl.get(i).tranCount();
		return count;
	}
	
	public String getStats() {
		return "(" + this.stateCount() + ", " + this.tranCount() + ")";
	}
}
