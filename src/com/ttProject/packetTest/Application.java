package com.ttProject.packetTest;

import java.util.HashMap;
import java.util.Map;

import org.red5.server.adapter.ApplicationAdapter;
import org.red5.server.api.IConnection;
import org.red5.server.api.IScope;
import org.red5.server.api.Red5;
import org.red5.server.api.stream.IBroadcastStream;

/**
 * Red5のadapter(ルームにしか興味ないので、そっちの処理しかしていない。)
 * @author taktod
 */
public class Application extends ApplicationAdapter {
	/** ルーム */
	private final Map<String, RoomInstance> roomMap = new HashMap<String, RoomInstance>();
	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean roomStart(IScope room) {
		RoomInstance roomInstance = new RoomInstance(room);
		roomMap.put(room.getName(), roomInstance);
		return super.roomStart(room);
	}
	/**
	 * {@inheritDoc}
	 */
	@Override
	public void roomStop(IScope room) {
		roomMap.remove(room.getName());
		super.roomStop(room);
	}
	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean roomConnect(IConnection conn, Object[] params) {
		boolean result = super.roomConnect(conn, params);
		if(!result) {
			return false;
		}
		RoomInstance roomInstance = roomMap.get(conn.getScope().getName());
		if(roomInstance != null) {
			return roomInstance.connect(conn, params);
		}
		return false;
	}
	/**
	 * {@inheritDoc}
	 */
	@Override
	public void roomDisconnect(IConnection conn) {
		RoomInstance roomInstance = roomMap.get(conn.getScope().getName());
		if(roomInstance != null) {
			roomInstance.disconnect(conn);
		}
		super.roomDisconnect(conn);
	}
	/**
	 * {@inheritDoc}
	 */
	@Override
	public void streamBroadcastStart(IBroadcastStream stream) {
		super.streamBroadcastStart(stream);
		IConnection conn = Red5.getConnectionLocal();
		RoomInstance roomInstance = roomMap.get(stream.getScope().getName());
		if(roomInstance != null) {
			roomInstance.streamBroadcastStart(conn, stream);
		}
		else {
			// ルーム接続でないコネクションの放送はすべて禁止
			stream.stop();
		}
	}
	/**
	 * {@inheritDoc}
	 */
	@Override
	public void streamBroadcastClose(IBroadcastStream stream) {
		IConnection conn = Red5.getConnectionLocal();
		RoomInstance roomInstance = roomMap.get(stream.getScope().getName());
		if(roomInstance != null) {
			roomInstance.streamBroadcastClose(conn, stream);
		}
		super.streamBroadcastClose(stream);
	}
}
