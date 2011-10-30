package com.ttProject.packetTest;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;

import org.red5.server.api.IClient;
import org.red5.server.api.IConnection;
import org.red5.server.api.IScope;
import org.red5.server.api.service.IServiceCapableConnection;
import org.red5.server.api.stream.IBroadcastStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ttProject.packetTest.flvbyte.FlvByteCreator;
import com.ttProject.packetTest.flvbyte.IFlvByteListener;

/**
 * Roomの処理を実行する
 * @author taktod
 */
public class RoomInstance implements IFlvByteListener {
	/** ロガー */
	private final Logger log;
	/** ルームスコープ */
	private final IScope scope;
	/** 放送しているコネクション(ここにはパケットデータを送信しない。) */
	private WeakReference<IConnection> broadcastConn = null;
	/** Flvデータ作成補助 */
	private FlvByteCreator flv = null;
	/**
	 * コンストラクタ
	 * @param scope
	 */
	public RoomInstance(IScope scope) {
		log = LoggerFactory.getLogger(getClass().getName() + "[" + scope.getName() + "]");
		this.scope = scope;
	}
	/**
	 * 接続イベント
	 * @param conn
	 * @param params
	 * @return
	 */
	public boolean connect(IConnection conn, Object[] params) {
		// 接続がきたら、いままでのメタデータを送信しておく
		if(flv != null) {
			log.info("send initial data to specific client");
			if(conn instanceof IServiceCapableConnection) {
				IServiceCapableConnection sconn = (IServiceCapableConnection) conn;
				sconn.invoke("flvHeader", new Object[]{flv.getHeader()});
				sconn.invoke("flvMetaData", new Object[]{flv.getMetaData()});
				for(byte[] data : flv.getInitialData()) {
					sconn.invoke("flvMetaData", new Object[]{data});
				}
			}
		}
		return true;
	}
	/**
	 * 切断イベント
	 * @param conn
	 */
	public void disconnect(IConnection conn) {
		// なにもしない。
	}
	/**
	 * ストリーム開始イベント
	 * @param conn
	 * @param stream
	 */
	public void streamBroadcastStart(IConnection conn, IBroadcastStream stream) {
		if(broadcastConn != null && broadcastConn.get() != null) {
			log.warn("stream is already established...");
			// すでに何らかの接続が確立されている。
			stream.close(); // ストリーム停止
			return;
		}
		log.info("streamStart");
		// 放送しているコネクションを保持しておく
		broadcastConn = new WeakReference<IConnection>(conn);
		flv = new FlvByteCreator(stream);
		flv.addEventListener(this);
	}
	/**
	 * ストリーム停止イベント
	 * @param conn
	 * @param stream
	 */
	public void streamBroadcastClose(IConnection conn, IBroadcastStream stream) {
		sendEndEvent();
		broadcastConn = null;
		// flvByteCreatorを無効化しておく。
		flv.removeEventListener(this);
		flv = null;
	}
	/**
	 * 初期データの送信要求イベント
	 */
	@Override
	public void changeInitDataEvent() {
		log.info("send initial data for all");
		for(IServiceCapableConnection sconn : getInvokeConnections()) {
			sconn.invoke("flvHeader", new Object[]{flv.getHeader()});
			sconn.invoke("flvMetaData", new Object[]{flv.getMetaData()});
			for(byte[] data : flv.getInitialData()) {
				sconn.invoke("flvMetaData", new Object[]{data});
			}
		}
	}
	/**
	 * データパケットイベント
	 */
	@Override
	public void packetEvent(byte[] data) {
		for(IServiceCapableConnection sconn : getInvokeConnections()) {
			sconn.invoke("flvData", new Object[]{data});
		}
	}
	private void sendEndEvent() {
		log.info("send end data for all");
		for(IServiceCapableConnection sconn : getInvokeConnections()) {
			sconn.invoke("flvEnd");
		}
	}
	/**
	 * ストリーム接続以外を見つけ出し送信接続リストを作成する。
	 * @return
	 */
	private Set<IServiceCapableConnection> getInvokeConnections() {
		Set<IServiceCapableConnection> sconns = new HashSet<IServiceCapableConnection>();
		for(IClient client : scope.getClients()) {
			for(IConnection conn : client.getConnections()) {
				if(conn != broadcastConn.get() && conn instanceof IServiceCapableConnection) {
					sconns.add((IServiceCapableConnection)conn);
				}
			}
		}
		return sconns;
	}
}
