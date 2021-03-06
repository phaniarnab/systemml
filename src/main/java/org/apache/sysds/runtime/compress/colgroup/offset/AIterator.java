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
package org.apache.sysds.runtime.compress.colgroup.offset;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Iterator interface, that returns a iterator of the indexes (not offsets)
 */
public abstract class AIterator {
	protected static final Log LOG = LogFactory.getLog(AIterator.class.getName());

	protected int index = 0;
	protected int dataIndex = 0;
	protected int offset = 0;

	/**
	 * Increment the pointers such that the both index and dataIndex is incremented to the next entry.
	 */
	public abstract void next();

	/**
	 * Get a boolean specifying if the iterator is done
	 * 
	 * @return A boolean that is true if there are more values contained in the Iterator.
	 */
	public abstract boolean hasNext();

	/**
	 * Get the current index value, note this correspond to a row index in the original matrix.
	 * 
	 * @return The current value pointed at.
	 */
	public int value() {
		return offset;
	}

	/**
	 * Get the current index value and increment the pointers
	 * 
	 * @return The current value pointed at.
	 */
	public int valueAndIncrement() {
		int x = offset;
		next();
		return x;
	}

	/**
	 * Get the current data index associated with the index returned from value.
	 * 
	 * @return The data Index.
	 */
	public int getDataIndex() {
		return dataIndex;
	}

	/**
	 * Get the current data index and increment the pointers using the next operator.
	 * 
	 * @return The current data index.
	 */
	public int getDataIndexAndIncrement() {
		int x = dataIndex;
		next();
		return x;
	}

	/**
	 * Skip values until index is achieved.
	 * 
	 * @param index The index to skip to.
	 * @return the index that follows or are equal to the skip to index.
	 */
	public int skipTo(int index) {
		while(hasNext() && offset < index)
			next();
		return offset;
	}

}
