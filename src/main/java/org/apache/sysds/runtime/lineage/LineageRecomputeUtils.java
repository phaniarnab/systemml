/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.runtime.lineage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang.NotImplementedException;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.common.Types.OpOpDG;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.OpOpN;
import org.apache.sysds.common.Types.ParamBuiltinOp;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.hops.DataGenOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.lops.compile.Dag;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DataExpression;
import org.apache.sysds.parser.DataIdentifier;
import org.apache.sysds.parser.Statement;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.BasicProgramBlock;
import org.apache.sysds.runtime.controlprogram.FunctionProgramBlock;
import org.apache.sysds.runtime.controlprogram.Program;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContextFactory;
import org.apache.sysds.runtime.instructions.Instruction;
import org.apache.sysds.runtime.instructions.InstructionParser;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.cp.CPInstruction.CPType;
import org.apache.sysds.runtime.instructions.cp.CPOperand;
import org.apache.sysds.runtime.instructions.cp.Data;
import org.apache.sysds.runtime.instructions.cp.DataGenCPInstruction;
import org.apache.sysds.runtime.instructions.cp.ScalarObjectFactory;
import org.apache.sysds.runtime.instructions.cp.VariableCPInstruction;
import org.apache.sysds.runtime.instructions.spark.RandSPInstruction;
import org.apache.sysds.runtime.instructions.spark.SPInstruction.SPType;
import org.apache.sysds.utils.Explain;
import org.apache.sysds.utils.Explain.ExplainCounts;
import org.apache.sysds.utils.Statistics;

public class LineageRecomputeUtils {
	private static final String LVARPREFIX = "lvar";
	public static final String LPLACEHOLDER = "IN#";
	private static final boolean DEBUG = false;
	public static final Map<Long, Map<String, LineageItem>> patchLiMap = new HashMap<>();
	private static final Map<Long, Map<String, Hop>> patchHopMap = new HashMap<>();

	public static Data parseNComputeLineageTrace(String mainTrace, String dedupPatches) {
		LineageItem root = LineageParser.parseLineageTrace(mainTrace);
		if (dedupPatches != null)
			LineageParser.parseLineageTraceDedup(dedupPatches);
		Data ret = computeByLineage(root);
		// Cleanup the statics
		patchLiMap.clear();
		patchHopMap.clear();
		return ret;
	}
	
	private static Data computeByLineage(LineageItem root) 
	{
		long rootId = root.getOpcode().equals("write") ?
			root.getInputs()[0].getId() : root.getId();
		String varname = LVARPREFIX + rootId;
		Program prog = new Program(null);
		
		// Recursively construct hops 
		root.resetVisitStatusNR();
		Map<Long, Hop> operands = new HashMap<>();
		Map<String, Hop> partDagRoots = new HashMap<>();
		rConstructHops(root, operands, partDagRoots, prog);
		Hop out = HopRewriteUtils.createTransientWrite(
			varname, operands.get(rootId));
		
		// Generate instructions
		ExecutionContext ec = ExecutionContextFactory.createContext();
		partDagRoots.put(varname, out);
		constructBasicBlock(partDagRoots, varname, prog);
		
		// Reset cache due to cleaned data objects
		LineageCache.resetCache();
		//execute instructions and get result
		if (DEBUG) {
			DMLScript.STATISTICS = true;
			ExplainCounts counts = Explain.countDistributedOperations(prog);
			System.out.println(Explain.display(null, prog, Explain.ExplainType.RUNTIME, counts));
		}
		ec.setProgram(prog);
		prog.execute(ec);
		if (DEBUG) {
			Statistics.stopRunTimer();
			System.out.println(Statistics.display(DMLScript.STATISTICS_COUNT));
		}
		return ec.getVariable(varname);
	}
	
	private static void constructBasicBlock(Map<String, Hop> partDagRoots, String dedupOut, Program prog) {
		Hop out = partDagRoots.get(dedupOut);
		// Compile and save
		BasicProgramBlock pb = new BasicProgramBlock(prog);
		pb.setInstructions(genInst(out));
		prog.addProgramBlock(pb);
	}
	
	
	private static void rConstructHops(LineageItem item, Map<Long, Hop> operands, Map<String, Hop> partDagRoots, Program prog) 
	{
		if (item.isVisited())
			return;
		
		//recursively process children (ordering by data dependencies)
		if (!item.isLeaf())
			for (LineageItem c : item.getInputs())
				rConstructHops(c, operands, partDagRoots, prog);
		
		//process current lineage item
		//NOTE: we generate instructions from hops (but without rewrites) to automatically
		//handle execution types, rmvar instructions, and rewiring of inputs/outputs
		switch (item.getType()) {
			case Creation: {
				if (item.getData().startsWith(LPLACEHOLDER)) {
					long phId = Long.parseLong(item.getData().substring(3));
					Hop input = operands.get(phId);
					operands.remove(phId);
					// Replace the placeholders with TReads
					operands.put(item.getId(), input); // order preserving
					break;
				}
				Instruction inst = InstructionParser.parseSingleInstruction(item.getData());
				
				if (inst instanceof DataGenCPInstruction) {
					DataGenCPInstruction rand = (DataGenCPInstruction) inst;
					HashMap<String, Hop> params = new HashMap<>();
					if( rand.getOpcode().equals("rand") ) {
						if( rand.output.getDataType() == DataType.TENSOR)
							params.put(DataExpression.RAND_DIMS, new LiteralOp(rand.getDims()));
						else {
							params.put(DataExpression.RAND_ROWS, new LiteralOp(rand.getRows()));
							params.put(DataExpression.RAND_COLS, new LiteralOp(rand.getCols()));
						}
						params.put(DataExpression.RAND_MIN, new LiteralOp(rand.getMinValue()));
						params.put(DataExpression.RAND_MAX, new LiteralOp(rand.getMaxValue()));
						params.put(DataExpression.RAND_PDF, new LiteralOp(rand.getPdf()));
						params.put(DataExpression.RAND_LAMBDA, new LiteralOp(rand.getPdfParams()));
						params.put(DataExpression.RAND_SPARSITY, new LiteralOp(rand.getSparsity()));
						params.put(DataExpression.RAND_SEED, new LiteralOp(rand.getSeed()));
					}
					else if( rand.getOpcode().equals("seq") ) {
						params.put(Statement.SEQ_FROM, new LiteralOp(rand.getFrom()));
						params.put(Statement.SEQ_TO, new LiteralOp(rand.getTo()));
						params.put(Statement.SEQ_INCR, new LiteralOp(rand.getIncr()));
					}
					Hop datagen = new DataGenOp(OpOpDG.valueOf(rand.getOpcode().toUpperCase()),
						new DataIdentifier("tmp"), params);
					datagen.setBlocksize(rand.getBlocksize());
					operands.put(item.getId(), datagen);
				} else if (inst instanceof VariableCPInstruction
						&& ((VariableCPInstruction) inst).isCreateVariable()) {
					String parts[] = InstructionUtils.getInstructionPartsWithValueType(inst.toString());
					DataType dt = DataType.valueOf(parts[4]);
					ValueType vt = dt == DataType.MATRIX ? ValueType.FP64 : ValueType.STRING;
					HashMap<String, Hop> params = new HashMap<>();
					params.put(DataExpression.IO_FILENAME, new LiteralOp(parts[2]));
					params.put(DataExpression.READROWPARAM, new LiteralOp(Long.parseLong(parts[6])));
					params.put(DataExpression.READCOLPARAM, new LiteralOp(Long.parseLong(parts[7])));
					params.put(DataExpression.READNNZPARAM, new LiteralOp(Long.parseLong(parts[8])));
					params.put(DataExpression.FORMAT_TYPE, new LiteralOp(parts[5]));
					DataOp pread = new DataOp(parts[1].substring(5), dt, vt, OpOpData.PERSISTENTREAD, params);
					pread.setFileName(parts[2]);
					operands.put(item.getId(), pread);
				}
				else if  (inst instanceof RandSPInstruction) {
					RandSPInstruction rand = (RandSPInstruction) inst;
					HashMap<String, Hop> params = new HashMap<>();
					if (rand.output.getDataType() == DataType.TENSOR)
						params.put(DataExpression.RAND_DIMS, new LiteralOp(rand.getDims()));
					else {
						params.put(DataExpression.RAND_ROWS, new LiteralOp(rand.getRows()));
						params.put(DataExpression.RAND_COLS, new LiteralOp(rand.getCols()));
					}
					params.put(DataExpression.RAND_MIN, new LiteralOp(rand.getMinValue()));
					params.put(DataExpression.RAND_MAX, new LiteralOp(rand.getMaxValue()));
					params.put(DataExpression.RAND_PDF, new LiteralOp(rand.getPdf()));
					params.put(DataExpression.RAND_LAMBDA, new LiteralOp(rand.getPdfParams()));
					params.put(DataExpression.RAND_SPARSITY, new LiteralOp(rand.getSparsity()));
					params.put(DataExpression.RAND_SEED, new LiteralOp(rand.getSeed()));
					Hop datagen = new DataGenOp(OpOpDG.RAND, new DataIdentifier("tmp"), params);
					datagen.setBlocksize(rand.getBlocksize());
					operands.put(item.getId(), datagen);
				}
				break;
			}
			case Instruction: {
				if (item.isDedup()) {
					// Create function call for each dedup entry 
					String[] parts = item.getOpcode().split(LineageDedupUtils.DEDUP_DELIM); //e.g. dedup_R_SB13_0
					String name = parts[2] + parts[1] + parts[3];  //loopId + outVar + pathId
					List<Hop> finputs = Arrays.stream(item.getInputs())
							.map(inp -> operands.get(inp.getId())).collect(Collectors.toList());
					String[] inputNames = new String[item.getInputs().length];
					for (int i=0; i<item.getInputs().length; i++)
						inputNames[i] = LPLACEHOLDER + i;  //e.g. IN#0, IN#1
					Hop funcOp = new FunctionOp(FunctionType.DML, DMLProgram.DEFAULT_NAMESPACE, 
							name, inputNames, finputs, new String[] {parts[1]}, false);

					// Cut the Hop dag after function calls 
					partDagRoots.put(parts[1], funcOp);
					// Compile the dag and save
					constructBasicBlock(partDagRoots, parts[1], prog);

					// Construct a Hop dag for the function body from the dedup patch, and compile
					Hop output = constructHopsDedupPatch(parts, inputNames, finputs, prog);
					// Create a TRead on the function o/p as a leaf for the next Hop dag
					// Use the function body root/return hop to propagate right data type
					operands.put(item.getId(), HopRewriteUtils.createTransientRead(parts[1], output));
					break;
				}
				CPType ctype = InstructionUtils.getCPTypeByOpcode(item.getOpcode());
				SPType stype = InstructionUtils.getSPTypeByOpcode(item.getOpcode());
				
				if (ctype != null) {
					switch (ctype) {
						case AggregateUnary: {
							Hop input = operands.get(item.getInputs()[0].getId());
							Hop aggunary = InstructionUtils.isUnaryMetadata(item.getOpcode()) ?
								HopRewriteUtils.createUnary(input, OpOp1.valueOfByOpcode(item.getOpcode())) :
								HopRewriteUtils.createAggUnaryOp(input, item.getOpcode());
							operands.put(item.getId(), aggunary);
							break;
						}
						case AggregateBinary: {
							Hop input1 = operands.get(item.getInputs()[0].getId());
							Hop input2 = operands.get(item.getInputs()[1].getId());
							Hop aggbinary = HopRewriteUtils.createMatrixMultiply(input1, input2);
							operands.put(item.getId(), aggbinary);
							break;
						}
						case AggregateTernary: {
							Hop input1 = operands.get(item.getInputs()[0].getId());
							Hop input2 = operands.get(item.getInputs()[1].getId());
							Hop input3 = operands.get(item.getInputs()[2].getId());
							Hop aggternary = HopRewriteUtils.createSum(
								HopRewriteUtils.createBinary(
								HopRewriteUtils.createBinary(input1, input2, OpOp2.MULT),
								input3, OpOp2.MULT));
							operands.put(item.getId(), aggternary);
							break;
						}
						case Unary:
						case Builtin: {
							Hop input = operands.get(item.getInputs()[0].getId());
							Hop unary = HopRewriteUtils.createUnary(input, item.getOpcode());
							operands.put(item.getId(), unary);
							break;
						}
						case Reorg: {
							operands.put(item.getId(), HopRewriteUtils.createReorg(
								operands.get(item.getInputs()[0].getId()), item.getOpcode()));
							break;
						}
						case Reshape: {
							ArrayList<Hop> inputs = new ArrayList<>();
							for(int i=0; i<5; i++)
								inputs.add(operands.get(item.getInputs()[i].getId()));
							operands.put(item.getId(), HopRewriteUtils.createReorg(inputs, ReOrgOp.RESHAPE));
							break;
						}
						case Binary: {
							//handle special cases of binary operations 
							String opcode = ("^2".equals(item.getOpcode()) 
								|| "*2".equals(item.getOpcode())) ? 
								item.getOpcode().substring(0, 1) : item.getOpcode();
							Hop input1 = operands.get(item.getInputs()[0].getId());
							Hop input2 = operands.get(item.getInputs()[1].getId());
							Hop binary = HopRewriteUtils.createBinary(input1, input2, opcode);
							operands.put(item.getId(), binary);
							break;
						}
						case Ternary: {
							operands.put(item.getId(), HopRewriteUtils.createTernary(
								operands.get(item.getInputs()[0].getId()), 
								operands.get(item.getInputs()[1].getId()), 
								operands.get(item.getInputs()[2].getId()), item.getOpcode()));
							break;
						}
						case Ctable: { //e.g., ctable 
							if( item.getInputs().length==3 )
								operands.put(item.getId(), HopRewriteUtils.createTernary(
									operands.get(item.getInputs()[0].getId()),
									operands.get(item.getInputs()[1].getId()),
									operands.get(item.getInputs()[2].getId()), OpOp3.CTABLE));
							else if( item.getInputs().length==5 )
								operands.put(item.getId(), HopRewriteUtils.createTernary(
									operands.get(item.getInputs()[0].getId()),
									operands.get(item.getInputs()[1].getId()),
									operands.get(item.getInputs()[2].getId()),
									operands.get(item.getInputs()[3].getId()),
									operands.get(item.getInputs()[4].getId()), OpOp3.CTABLE));
							break;
						}
						case BuiltinNary: {
							String opcode = item.getOpcode().equals("n+") ? "plus" : item.getOpcode();
							operands.put(item.getId(), HopRewriteUtils.createNary(
								OpOpN.valueOf(opcode.toUpperCase()), createNaryInputs(item, operands)));
							break;
						}
						case ParameterizedBuiltin: {
							operands.put(item.getId(), constructParameterizedBuiltinOp(item, operands));
							break;
						}
						case MatrixIndexing: {
							operands.put(item.getId(), constructIndexingOp(item, operands));
							break;
						}
						case MMTSJ: {
							//TODO handling of tsmm type left and right -> placement transpose
							Hop input = operands.get(item.getInputs()[0].getId());
							Hop aggunary = HopRewriteUtils.createMatrixMultiply(
								HopRewriteUtils.createTranspose(input), input);
							operands.put(item.getId(), aggunary);
							break;
						}
						case Variable: {
							if( item.getOpcode().startsWith("cast") )
								operands.put(item.getId(), HopRewriteUtils.createUnary(
									operands.get(item.getInputs()[0].getId()),
									OpOp1.valueOfByOpcode(item.getOpcode())));
							else //cpvar, write
								operands.put(item.getId(), operands.get(item.getInputs()[0].getId()));
							break;
						}
						default:
							throw new DMLRuntimeException("Unsupported instruction "
								+ "type: " + ctype.name() + " (" + item.getOpcode() + ").");
					}
				}
				else if( stype != null ) {
					switch(stype) {
						case Reblock: {
							Hop input = operands.get(item.getInputs()[0].getId());
							input.setBlocksize(ConfigurationManager.getBlocksize());
							input.setRequiresReblock(true);
							operands.put(item.getId(), input);
							break;
						}
						case Checkpoint: {
							Hop input = operands.get(item.getInputs()[0].getId());
							operands.put(item.getId(), input);
							break;
						}
						case MatrixIndexing: {
							operands.put(item.getId(), constructIndexingOp(item, operands));
							break;
						}
						case GAppend: {
							operands.put(item.getId(), HopRewriteUtils.createBinary(
								operands.get(item.getInputs()[0].getId()),
								operands.get(item.getInputs()[1].getId()), OpOp2.CBIND));
							break;
						}
						default:
							throw new DMLRuntimeException("Unsupported instruction "
								+ "type: " + stype.name() + " (" + item.getOpcode() + ").");
					}
				}
				else
					throw new DMLRuntimeException("Unsupported instruction: " + item.getOpcode());
				break;
			}
			case Literal: {
				CPOperand op = new CPOperand(item.getData());
				operands.put(item.getId(), ScalarObjectFactory
					.createLiteralOp(op.getValueType(), op.getName()));
				break;
			}
			case Dedup: {
				throw new NotImplementedException();
			}
		}
		
		item.setVisited();
	}

	private static Hop constructHopsDedupPatch(String[] parts, String[] inputs, List<Hop> inpHops, Program prog) {
		// Construct and compile the function body
		String outname = parts[1];
		Long pathId = Long.parseLong(parts[3]);
		// Return if this patch is already compiled
		if (patchHopMap.containsKey(pathId) && patchHopMap.get(pathId).containsKey(outname))
			return patchHopMap.get(pathId).get(outname);

		// Construct a Hop dag
		LineageItem patchRoot = patchLiMap.get(pathId).get(outname);
		patchRoot.resetVisitStatusNR();
		Map<Long, Hop> operands = new HashMap<>();
		// Create TRead on the function inputs
		//FIXME: the keys of operands can be replaced inside rConstructHops
		for (int i=0; i<inputs.length; i++)
			operands.put((long)i, HopRewriteUtils.createTransientRead(inputs[i], inpHops.get(i))); //order preserving
		rConstructHops(patchRoot, operands, null, null);
		Hop out = HopRewriteUtils.createTransientWrite(outname, operands.get(patchRoot.getId()));
		if (!patchHopMap.containsKey(pathId))
			patchHopMap.put(pathId, new HashMap<>());
		patchHopMap.get(pathId).put(outname, out);
		
		// Compile to instructions and save as a FunctionProgramBlock
		List<DataIdentifier> funcInputs = new ArrayList<>();
		for (int i=0; i<inpHops.size(); i++)
			funcInputs.add(new DataIdentifier(inputs[i], inpHops.get(i).getDataType(), inpHops.get(i).getValueType()));
		List<DataIdentifier> funcOutput = new ArrayList<>(Arrays.asList(new DataIdentifier(outname)));
		// TODO: multi-return function
		FunctionProgramBlock fpb = new FunctionProgramBlock(prog, funcInputs, funcOutput);
		BasicProgramBlock pb = new BasicProgramBlock(prog);
		pb.setInstructions(genInst(out));
		fpb.addProgramBlock(pb);
		prog.addFunctionProgramBlock(DMLProgram.DEFAULT_NAMESPACE, parts[2]+parts[1]+parts[3], fpb);
		//fpb.setRecompileOnce(true);
		return out;
	}
	
	private static ArrayList<Instruction> genInst (Hop root) {
		Dag<Lop> dag = new Dag<>();
		Lop lops = root.constructLops();
		lops.addToDag(dag);
		return dag.getJobs(null, ConfigurationManager.getDMLConfig());
	}

	private static Hop[] createNaryInputs(LineageItem item, Map<Long, Hop> operands) {
		int len = item.getInputs().length;
		Hop[] ret = new Hop[len];
		for( int i=0; i<len; i++ )
			ret[i] = operands.get(item.getInputs()[i].getId());
		return ret;
	}

	private static Hop constructParameterizedBuiltinOp(LineageItem item, Map<Long, Hop> operands) {
		String opcode = item.getOpcode();
		Hop target = operands.get(item.getInputs()[0].getId());
		LinkedHashMap<String,Hop> args = new LinkedHashMap<>();
		if( opcode.equals("groupedagg") ) {
			args.put("target", target);
			args.put(Statement.GAGG_GROUPS, operands.get(item.getInputs()[1].getId()));
			args.put(Statement.GAGG_WEIGHTS, operands.get(item.getInputs()[2].getId()));
			args.put(Statement.GAGG_FN, operands.get(item.getInputs()[3].getId()));
			args.put(Statement.GAGG_NUM_GROUPS, operands.get(item.getInputs()[4].getId()));
		}
		else if (opcode.equalsIgnoreCase("rmempty")) {
			args.put("target", target);
			args.put("margin", operands.get(item.getInputs()[1].getId()));
			args.put("select", operands.get(item.getInputs()[2].getId()));
		}
		else if(opcode.equalsIgnoreCase("replace")) {
			args.put("target", target);
			args.put("pattern", operands.get(item.getInputs()[1].getId()));
			args.put("replacement", operands.get(item.getInputs()[2].getId()));
		}
		else if(opcode.equalsIgnoreCase("rexpand")) {
			args.put("target", target);
			args.put("max", operands.get(item.getInputs()[1].getId()));
			args.put("dir", operands.get(item.getInputs()[2].getId()));
			args.put("cast", operands.get(item.getInputs()[3].getId()));
			args.put("ignore", operands.get(item.getInputs()[4].getId()));
		}
		
		return HopRewriteUtils.createParameterizedBuiltinOp(
			target, args, ParamBuiltinOp.valueOf(opcode.toUpperCase()));
	}

	private static Hop constructIndexingOp(LineageItem item, Map<Long, Hop> operands) {
		Hop input = operands.get(item.getInputs()[0].getId());
		if( "rightIndex".equals(item.getOpcode()) )
			return HopRewriteUtils.createIndexingOp(input,
				operands.get(item.getInputs()[1].getId()), //rl
				operands.get(item.getInputs()[2].getId()), //ru
				operands.get(item.getInputs()[3].getId()), //cl
				operands.get(item.getInputs()[4].getId())); //cu
		else if( "leftIndex".equals(item.getOpcode()) 
				|| "mapLeftIndex".equals(item.getOpcode()) )
			return HopRewriteUtils.createLeftIndexingOp(input,
				operands.get(item.getInputs()[1].getId()), //rhs
				operands.get(item.getInputs()[2].getId()), //rl
				operands.get(item.getInputs()[3].getId()), //ru
				operands.get(item.getInputs()[4].getId()), //cl
				operands.get(item.getInputs()[5].getId())); //cu
		throw new DMLRuntimeException("Unsupported opcode: "+item.getOpcode());
	}
}
