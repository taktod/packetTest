package com.ttProject.packetTest;

import org.red5.server.adapter.ApplicationAdapter;
import org.red5.server.api.IConnection;
import org.red5.server.api.IScope;
import org.red5.server.api.service.IServiceCapableConnection;
import org.red5.server.api.stream.IBroadcastStream;

import com.ttProject.packetTest.flvbyte.FlvByteCreator;
import com.ttProject.packetTest.flvbyte.IFlvByteListener;

public class Application extends ApplicationAdapter implements IFlvByteListener{
	private IServiceCapableConnection target = null;
	private FlvByteCreator flv = new FlvByteCreator();
	@Override
	public boolean appStart(IScope scope) {
		flv.addEventListener(this);
		return super.appStart(scope);
	}
	@Override
	public boolean roomConnect(IConnection conn, Object[] params) {
		if(target != null) {
			System.out.println("target is already setted up...");
			return false;
		}
		if(conn instanceof IServiceCapableConnection) {
			target = (IServiceCapableConnection)conn;
			target.invoke("flvHeader", new Object[]{flv.getHeader()}, null);
			target.invoke("flvMetaData", new Object[]{flv.getMetaData()}, null);
			for(byte[] data : flv.getInitialData()) {
				target.invoke("flvMetaData", new Object[]{data}, null);
			}
		}
		return super.roomConnect(conn, params);
	}
	@Override
	public void roomDisconnect(IConnection conn) {
		if(conn.equals(target)) {
			target = null;
		}
		super.roomDisconnect(conn);
	}
	/**
	 * 放送を開始したとき・・・
	 */
	@Override
	public void streamBroadcastStart(IBroadcastStream stream) {
		stream.addStreamListener(flv);
	}
	@Override
	public void streamBroadcastClose(IBroadcastStream stream) {
		super.streamBroadcastClose(stream);
	}
	@Override
	public void packetCreated(byte[] data) {
		if(target != null) {
			target.invoke("flvData", new Object[]{data}, null);
		}
	}
}
