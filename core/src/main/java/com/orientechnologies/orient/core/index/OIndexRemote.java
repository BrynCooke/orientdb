/*
 * Copyright 1999-2010 Luca Garulli (l.garulli--at--orientechnologies.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.orientechnologies.orient.core.index;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import com.orientechnologies.common.listener.OProgressListener;
import com.orientechnologies.orient.core.command.OCommandRequest;
import com.orientechnologies.orient.core.db.record.ODatabaseRecord;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.record.ORecord;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.sql.OCommandSQL;

/**
 * Proxied index.
 * 
 * @author Luca Garulli
 * 
 */
@SuppressWarnings("unchecked")
public class OIndexRemote implements OIndex {
	private final ODatabaseRecord	database;
	private final String					wrappedType;
	private final ORID						rid;

	private String								name;

	private final static String		QUERY_GET				= "select from index:%s where key = ?";
	private final static String		QUERY_GET_RANGE	= "select from index:%s where key between ? and ?";
	private final static String		QUERY_PUT				= "insert into index:%s (key,value) values (%s,%s)";
	private final static String		QUERY_REMOVE		= "delete from index:%s where key = %s";
	private final static String		QUERY_REMOVE2		= "delete from index:%s where key = %s and value = %s";
	private final static String		QUERY_REMOVE3		= "delete from index:%s where value = ?";
	private final static String		QUERY_CONTAINS	= "select count(*) as size from	index:%s where key = ?";
	private final static String		QUERY_SIZE			= "select count(*) as size from index:%s";
	private final static String		QUERY_KEYS			= "select key from index:%s";
	private final static String		QUERY_ENTRIES		= "select key, value from index:%s";
	private final static String		QUERY_CLEAR			= "delete from index:%s";

	public OIndexRemote(final ODatabaseRecord iDatabase, final String iName, final String iWrappedType, final ORID iRid) {
		this.database = iDatabase;
		this.name = iName;
		this.wrappedType = iWrappedType;
		this.rid = iRid;
	}

	public OIndex create(final String iName, final ODatabaseRecord iDatabase, final String iClusterIndexName,
			final int[] iClusterIdsToIndex, final OProgressListener iProgressListener, final boolean iAutomatic) {
		name = iName;
		// final OCommandRequest cmd = formatCommand(QUERY_CREATE, name, wrappedType);
		// database.command(cmd).execute();
		return this;
	}

	public OIndex delete() {
		// final OCommandRequest cmd = formatCommand(QUERY_DROP, name);
		// database.command(cmd).execute();
		return this;
	}

	public Collection<OIdentifiable> get(final Object iKey) {
		final OCommandRequest cmd = formatCommand(QUERY_GET, name);
		return (Collection<OIdentifiable>) database.command(cmd).execute(iKey);
	}

	public Collection<OIdentifiable> getBetween(final Object iRangeFrom, final Object iRangeTo) {
		final OCommandRequest cmd = formatCommand(QUERY_GET_RANGE, name);
		return (Collection<OIdentifiable>) database.command(cmd).execute(iRangeFrom, iRangeTo);
	}

	public boolean contains(final Object iKey) {
		final OCommandRequest cmd = formatCommand(QUERY_CONTAINS, name);
		final List<ODocument> result = database.command(cmd).execute();
		return (Long) result.get(0).field("size") > 0;
	}

	public OIndex put(Object iKey, final OIdentifiable iValue) {
		if (iKey instanceof String)
			iKey = "'" + iKey + "'";

		if (iValue instanceof ORecord<?> && !iValue.getIdentity().isValid())
			// SAVE IT BEFORE TO PUT
			((ORecord<?>) iValue).save();

		final OCommandRequest cmd = formatCommand(QUERY_PUT, name, iKey, iValue.getIdentity());
		database.command(cmd).execute();
		return this;
	}

	public boolean remove(Object iKey) {
		if (iKey instanceof String)
			iKey = "'" + iKey + "'";

		final OCommandRequest cmd = formatCommand(QUERY_REMOVE, name, iKey);
		return Boolean.parseBoolean((String) database.command(cmd).execute(iKey));
	}

	public boolean remove(Object iKey, final OIdentifiable iRID) {
		if (iKey instanceof String)
			iKey = "'" + iKey + "'";

		final OCommandRequest cmd = formatCommand(QUERY_REMOVE2, name, iKey, iRID.getIdentity());
		return Boolean.parseBoolean((String) database.command(cmd).execute(iKey, iRID));
	}

	public int remove(final OIdentifiable iRecord) {
		final OCommandRequest cmd = formatCommand(QUERY_REMOVE3, name, iRecord.getIdentity());
		return (Integer) database.command(cmd).execute(iRecord);
	}

	public OIndex clear() {
		final OCommandRequest cmd = formatCommand(QUERY_CLEAR, name);
		database.command(cmd).execute();
		return this;
	}

	public Iterable<Object> keys() {
		final OCommandRequest cmd = formatCommand(QUERY_KEYS, name);
		return (Iterable<Object>) database.command(cmd).execute();
	}

	public Iterator<Entry<Object, Set<OIdentifiable>>> iterator() {
		final OCommandRequest cmd = formatCommand(QUERY_ENTRIES, name);
		return (Iterator<Entry<Object, Set<OIdentifiable>>>) database.command(cmd).execute();
	}

	public long getSize() {
		final OCommandRequest cmd = formatCommand(QUERY_SIZE, name);
		final List<ODocument> result = database.command(cmd).execute();
		return (Long) result.get(0).field("size");
	}

	public void unload() {

	}

	public boolean isAutomatic() {
		return false;
	}

	public String getName() {
		return name;
	}

	/**
	 * Do nothing.
	 */
	public OIndex lazySave() {
		return this;
	}

	public String getType() {
		return wrappedType;
	}

	public void setCallback(OIndexCallback iCallback) {
	}

	public ODocument getConfiguration() {
		return null;
	}

	public ORID getIdentity() {
		return rid;
	}

	public void commit(List<ODocument> iEntries) {
		// TODO
	}

	private OCommandRequest formatCommand(final String iTemplate, final Object... iArgs) {
		final String text = String.format(iTemplate, iArgs);
		return new OCommandSQL(text);
	}
}