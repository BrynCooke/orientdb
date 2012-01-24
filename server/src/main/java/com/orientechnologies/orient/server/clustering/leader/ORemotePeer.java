/*
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
package com.orientechnologies.orient.server.clustering.leader;

import java.io.IOException;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.SecretKey;

import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.orient.client.remote.OStorageRemoteThread;
import com.orientechnologies.orient.core.config.OContextConfiguration;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.enterprise.channel.binary.OAsynchChannelServiceThread;
import com.orientechnologies.orient.enterprise.channel.binary.OChannelBinaryClient;
import com.orientechnologies.orient.enterprise.channel.distributed.OChannelDistributedProtocol;
import com.orientechnologies.orient.server.replication.ODistributedRemoteAsynchEventListener;

/**
 * Contains all the information about a cluster node managed by the Leader.
 * 
 * @author Luca Garulli (l.garulli--at--orientechnologies.com)
 * 
 */
public class ORemotePeer {
	public enum STATUS {
		DISCONNECTED, CONNECTING, CONNECTED, UNREACHABLE, SYNCHRONIZING
	}

	private String											id;
	public String												networkAddress;
	public int													networkPort;
	public Date													joinedOn;
	private OLeaderNode									leader;
	private OChannelBinaryClient				channel;
	private OContextConfiguration				configuration;
	private volatile STATUS							status					= STATUS.DISCONNECTED;
	private int													sessionId;
	private OAsynchChannelServiceThread	serviceThread;

	public ORemotePeer(final OLeaderNode iNode, final String iServerAddress, final int iServerPort) {
		leader = iNode;
		networkAddress = iServerAddress;
		networkPort = iServerPort;
		joinedOn = new Date();
		configuration = new OContextConfiguration();
		id = networkAddress + ":" + networkPort;
		status = STATUS.CONNECTING;
	}

	/**
	 * Connects the current leader to a remote node peer.
	 * 
	 * @param iTimeout
	 * @param iClusterName
	 * @param iSecurityKey
	 * @return true if the node has been connected, otherwise false. False is the case the other node is a Leader too and wins the
	 *         conflicts.
	 * @throws IOException
	 */
	public boolean connect(final int iTimeout, final String iClusterName, final SecretKey iSecurityKey) throws IOException {
		configuration.setValue(OGlobalConfiguration.NETWORK_SOCKET_TIMEOUT, iTimeout);

		channel = new OChannelBinaryClient(networkAddress, networkPort, configuration);
//		serviceThread = new OAsynchChannelServiceThread(new ODistributedRemoteAsynchEventListener(leader.getManager(), null, id),
//				channel);

		OLogManager.instance().warn(this, "Cluster <%s>: received joining request from peer node %s:%d. Checking authorizations...",
				iClusterName, networkAddress, networkPort);

		sessionId = OStorageRemoteThread.getNextConnectionId();

		// CONNECT TO THE SERVER
		channel.writeByte(OChannelDistributedProtocol.REQUEST_DISTRIBUTED_LEADER_CONNECT);
		channel.writeInt(sessionId);

		final ODocument doc = new ODocument();
		doc.field("clusterName", iClusterName);
		doc.field("clusterKey", iSecurityKey.getEncoded());
		doc.field("leaderNodeAddress", leader.getManager().getId());
		doc.field("leaderNodeRunningSince", leader.getManager().getRunningSince());
		channel.writeBytes(doc.toStream());
		channel.flush();

		final ODocument cfg;

		beginResponse(sessionId);
		try {
			sessionId = channel.readInt();

			final byte connectedAsPeer = channel.readByte();
			if (connectedAsPeer == 0) {
				OLogManager
						.instance()
						.warn(
								this,
								"Cluster <%s>: remote server node %s:%d has refused the connection because it's the new Leader. Switching to be a Peer Node...",
								leader.getManager().getConfig().name, networkAddress, networkPort);
				leader.getManager().becomePeer(null);
				return false;
			}

			OLogManager.instance().info(this, "Cluster <%s>: joined peer node %s:%d", iClusterName, networkAddress, networkPort);

			// READ PEER DATABASES
			cfg = new ODocument().fromStream(channel.readBytes());
		} finally {
			endResponse();
		}

		// SEND BACK THE LIST OF NODES HANDLING ITS DATABASE
		final ODocument answer = leader.updatePeerDatabases(id, cfg);
		channel.writeBytes(answer.toStream());
		channel.flush();

		status = STATUS.CONNECTED;
		return true;
	}

	public void sendConfiguration(final ODocument iConfiguration) {
		OLogManager.instance().info(this, "Sending distributed configuration to server node %s:%d...", networkAddress, networkPort);

		try {
			channel.beginRequest();
			try {
				channel.writeByte(OChannelDistributedProtocol.REQUEST_DISTRIBUTED_DB_CONFIG);
				channel.writeInt(sessionId);
				channel.writeBytes(iConfiguration.toStream());

			} finally {
				channel.endRequest();
			}

			try {
				beginResponse(sessionId);
			} finally {
				endResponse();
			}

		} catch (Exception e) {
			OLogManager.instance().warn(this, "Error on sending configuration to server node", toString());
		}
	}

	public boolean sendHeartBeat(final int iNetworkTimeout) throws InterruptedException {
		if (channel == null)
			return false;

		if (status != STATUS.CONNECTED)
			return false;

		configuration.setValue(OGlobalConfiguration.NETWORK_SOCKET_TIMEOUT, iNetworkTimeout);
		OLogManager.instance()
				.debug(this, "Sending keepalive message to distributed server node %s:%d...", networkAddress, networkPort);

		try {
			channel.beginRequest();
			try {
				channel.writeByte(OChannelDistributedProtocol.REQUEST_DISTRIBUTED_HEARTBEAT);
				channel.writeInt(sessionId);
			} finally {
				channel.endRequest();
			}

			try {
				channel.beginResponse(sessionId);
			} finally {
				channel.endResponse();
			}

		} catch (Exception e) {
			OLogManager.instance().debug(this, "Error on sending heartbeat to server node", e, toString());
			return false;
		}

		return true;
	}

	public void beginResponse(final int iSessionId) throws IOException {
		channel.beginResponse(iSessionId);
	}

	public void beginResponse() throws IOException {
		if (channel != null)
			channel.beginResponse(sessionId);
	}

	public void endResponse() {
		if (channel != null)
			channel.endResponse();
	}

	/**
	 * Check if a remote node is really connected.
	 * 
	 * @return true if it's connected, otherwise false
	 */
	public boolean checkConnection() {
		boolean connected = false;

		if (channel != null && channel.socket != null)
			try {
				connected = channel.socket.isConnected();
			} catch (Exception e) {
			}

		if (!connected)
			status = STATUS.DISCONNECTED;

		return connected;
	}

	public void disconnect() {
		if (channel != null)
			channel.close();
		channel = null;
		if (serviceThread != null)
			serviceThread.sendShutdown();
	}

	@Override
	public String toString() {
		return id;
	}

	public STATUS getStatus() {
		return status;
	}

	public Object getId() {
		return id;
	}
}