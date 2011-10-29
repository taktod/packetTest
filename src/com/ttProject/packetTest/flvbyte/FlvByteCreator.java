package com.ttProject.packetTest.flvbyte;

import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.mina.core.buffer.IoBuffer;
import org.red5.io.ITag;
import org.red5.io.amf.Output;
import org.red5.io.flv.FLVHeader;
import org.red5.io.flv.impl.Tag;
import org.red5.io.object.Serializer;
import org.red5.io.utils.IOUtils;
import org.red5.server.api.stream.IBroadcastStream;
import org.red5.server.api.stream.IStreamListener;
import org.red5.server.api.stream.IStreamPacket;
import org.red5.server.net.rtmp.event.IRTMPEvent;
import org.red5.server.stream.IStreamData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * flvのバイトデータを作成するプログラム
 * これをクライアントにわたしてやってNetStream.appendBytesでデータをなんとかする予定
 * @author taktod
 */
public class FlvByteCreator implements IStreamListener {
	/** ロガー */
	private static final Logger log = LoggerFactory.getLogger(FlvByteCreator.class);
	/** ヘッダー長定義 */
	private final static int TAG_HEADER_LENGTH = 11;
	/** FLVヘッダー長定義 */
	private final static int HEADER_LENDTH = 9;
	/** コーデック判定情報 */
	private final static int CODEC_AUDIO_AAC = 10; // AAC
	private final static int CODEC_VIDEO_AVC = 7; // H.264
	/** flvパケット監視 */
	private final List<IFlvByteListener> listeners;
	/** 初期化データ(FLVHeader情報) */
	private byte[] flvHeader;
	/** 初期化データ(オリジナルメタデータ) */
	private byte[] metaData;
	/** 初期化データ(メタデータ、動画初期フラグ(H.264)、音声初期フラグ(AAC)) */
	private final List<byte[]> initialData;
	/** コーデック情報(ビデオ) */
	private volatile int videoCodecId = -1;
	/** コーデック情報(音声) */
	private volatile int audioCodecId = -1;
	/*
	 * 方針：必要な処理は
	 * 初期値の作成(Flvヘッダ、メタデータの作成)
	 * 放送パケットをキャプチャする動作
	 * この２つのみ。
	 */
	public FlvByteCreator(IBroadcastStream stream) {
		listeners = new ArrayList<IFlvByteListener>();
		initialData = new ArrayList<byte[]>();
		// FLVヘッダーを作成する。
		makeFlvHeader();
		// メタデータ仮を作成する。
		makeMetaData();
		stream.addStreamListener(this);
	}
	/**
	 * 生成パケット監視リスナー設置
	 * @param listener
	 */
	public void addEventListener(IFlvByteListener listener) {
		listeners.add(listener);
	}
	/**
	 * 生成パケット監視リスナー解除
	 * @param listener
	 */
	public void removeEventListener(IFlvByteListener listener) {
		listeners.remove(listener);
	}
	/**
	 * ヘッダー情報を作成し保持しておく。
	 */
	private void makeFlvHeader() {
		FLVHeader flvHeader = new FLVHeader();
		flvHeader.setFlagAudio(audioCodecId != -1);
		flvHeader.setFlagVideo(videoCodecId != -1);
		IoBuffer header = IoBuffer.allocate(HEADER_LENDTH + 4);
		flvHeader.write(header);
		header.flip();
		this.flvHeader = header.array();
		header.clear();
	}
	/**
	 * メタデータ構築
	 */
	private void makeMetaData() {
		// 初期化
		final IoBuffer buf = IoBuffer.allocate(192);
		final Output out;
		final ITag tag;
		final int bodySize;
		final byte dataType;
		final int previousTagSize;
		final int totalTagSize;
		
		// 事前情報設定
		buf.setAutoExpand(true);
		out = new Output(buf);
		out.writeString("onMetaData");
		Map<Object, Object> params = new HashMap<Object, Object>();
		params.put("server", "ttProject flvByteCreator");
		params.put("creationdate", GregorianCalendar.getInstance().getTime().toString());
		if(videoCodecId != -1) {
			params.put("videocodecid", videoCodecId);
		}
		else {
			params.put("novideocodec", 0);
		}
		if(audioCodecId != -1) {
			params.put("audiocodecid", audioCodecId);
		}
		else {
			params.put("noaudiocodec", 0);
		}
		out.writeMap(params, new Serializer());
		buf.flip();
		// Tag作成情報セットアップ
		tag = new Tag(ITag.TYPE_METADATA, 0, buf.limit(), buf, 0);
		dataType = tag.getDataType();
		bodySize = tag.getBodySize();
		previousTagSize = TAG_HEADER_LENGTH + bodySize;
		totalTagSize = TAG_HEADER_LENGTH + bodySize + 4;

		// 動作準備
		final IoBuffer tagBuffer = IoBuffer.allocate(totalTagSize);
		byte[] bodyBuf = new byte[bodySize];

		// データ抜き取り
		tag.getBody().get(bodyBuf);
		// コーデック判定フラグ保持
		tagBuffer.put(dataType); // データタイプ(1byte)
		IOUtils.writeMediumInt(tagBuffer, bodySize); // 情報サイズ(3byte)
		IOUtils.writeExtendedMediumInt(tagBuffer, 0); // タイムスタンプ強制0(4byte)
		IOUtils.writeMediumInt(tagBuffer, 0); // 0x00(3byte)
		tagBuffer.put(bodyBuf); // 情報(任意サイズ)
		tagBuffer.putInt(previousTagSize); // 終了時、このタグ情報の長さを応答(4byte)
		tagBuffer.flip();
		metaData = tagBuffer.array(); // データを保持
		tagBuffer.clear();
		buf.clear();
	}
	/**
	 * TagからFlvのByteデータを生成する。
	 * @param tag
	 * @return
	 */
	private byte[] getTagData(ITag tag) {
		final int bodySize = tag.getBodySize();
		byte[] result = null;
		if(bodySize > 0) {
			// 情報収集
			final byte dataType = tag.getDataType();
			final int previousTagSize = TAG_HEADER_LENGTH + bodySize;
			final int totalTagSize = TAG_HEADER_LENGTH + bodySize + 4;
			final int timeStamp = tag.getTimestamp();
			final byte firstByte;
			final int id;

			// 動作準備
			IoBuffer tagBuffer = IoBuffer.allocate(totalTagSize);
			byte[] bodyBuf = new byte[bodySize];

			tag.getBody().get(bodyBuf);
			// コーデック判定フラグ保持
			firstByte = bodyBuf[0];
			tagBuffer.put(dataType); // データタイプ(1byte)
			IOUtils.writeMediumInt(tagBuffer, bodySize); // 情報サイズ(3byte)
			if(dataType == ITag.TYPE_METADATA) { // タイムスタンプ(4byte)
				IOUtils.writeExtendedMediumInt(tagBuffer, 0);
			}
			else {
				IOUtils.writeExtendedMediumInt(tagBuffer, timeStamp);
			}
			IOUtils.writeMediumInt(tagBuffer, 0); // 0x00(3byte)
			tagBuffer.put(bodyBuf); // 情報(任意サイズ)
			tagBuffer.putInt(previousTagSize); // 終了時、このタグ情報の長さを応答(4byte)
			tagBuffer.flip();
			result = tagBuffer.array();
			tagBuffer.clear();

			switch(dataType) {
			case ITag.TYPE_AUDIO:
				if(audioCodecId == -1) {
					// コーデック判定
					id = firstByte & 0xFF;
					audioCodecId = (id & ITag.MASK_SOUND_FORMAT) >> 4;
					if(audioCodecId == CODEC_AUDIO_AAC) { // AACなら初期バイトを保持しておく
						initialData.add(result);
					}
					makeFlvHeader();
					makeMetaData();
					// 変更を検知したので、イベントを送信する。
					for(IFlvByteListener listener : listeners) {
						listener.changeInitDataEvent();
					}
				}
				break;
			case ITag.TYPE_VIDEO:
				if(videoCodecId == -1) {
					// コーデック判定
					id = firstByte & 0xFF;
					videoCodecId = (id & ITag.MASK_VIDEO_CODEC);
					if(videoCodecId == CODEC_VIDEO_AVC) { // H.264なら初期バイトを保持しておく
						initialData.add(result);
					}
					makeFlvHeader();
					makeMetaData();
					// 変更を検知したので、イベントを送信する。
					for(IFlvByteListener listener : listeners) {
						listener.changeInitDataEvent();
					}
				}
				break;
			case ITag.TYPE_METADATA:
				initialData.add(result);
				break;
			}
		}
		return result;
	}
	/**
	 * ヘッダーデータ応答
	 * @return
	 */
	public byte[] getHeader() {
		return flvHeader;
	}
	/**
	 * メタデータ応答
	 * @return
	 */
	public byte[] getMetaData() {
		return metaData;
	}
	/**
	 * 初期データ応答
	 * @return
	 */
	public List<byte[]> getInitialData() {
		return initialData;
	}
	/**
	 * 放送パケットをキャプチャする動作
	 */
	@Override
	public void packetReceived(IBroadcastStream stream, IStreamPacket packet) {
		try {
			final byte[] byteData;
			// rtmpEventやstreamDataでないパケットの場合は処理しない
			if(!(packet instanceof IRTMPEvent) || !(packet instanceof IStreamData)) {
				return;
			}
			IRTMPEvent rtmpEvent = (IRTMPEvent) packet;
			// サイズを確認
			if(rtmpEvent.getHeader().getSize() == 0) {
				return;
			}
			// データを加工する。
			ITag tag = new Tag();
			tag.setDataType(rtmpEvent.getDataType());
			tag.setTimestamp(rtmpEvent.getTimestamp());
			@SuppressWarnings("rawtypes")
			IoBuffer data = ((IStreamData)rtmpEvent).getData().asReadOnlyBuffer();
			tag.setBodySize(data.limit());
			tag.setBody(data);
			byteData = getTagData(tag);
			// リスナーにデータの送信を要求する。
			for(IFlvByteListener listener : listeners) {
				listener.packetEvent(byteData);
			}
		}
		catch (Exception e) {
			log.error("packetRecieved", e);
		}
	}
}
