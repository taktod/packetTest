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
	public FlvByteCreator() {
		listeners = new ArrayList<IFlvByteListener>();
		initialData = new ArrayList<byte[]>();
		// FLVヘッダーを作成する。
		makeFlvHeader();
		// メタデータ仮を作成する。
		makeMetaData();
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
		// 音声データ、動画データは本当にながれてくるかわからないけど、とりあえずフラグ上はONにしておく。
		flvHeader.setFlagAudio(true);
		flvHeader.setFlagVideo(true);
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
		buf.setAutoExpand(true);
		final Output out = new Output(buf);
		final ITag tag;
		final int bodySize;
		final byte dataType;
		final int previousTagSize;
		final int totalTagSize;
		
		// 事前情報設定
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
					makeMetaData();
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
					makeMetaData();
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
			data.clear();
			for(IFlvByteListener listener : listeners) {
				listener.packetCreated(byteData);
			}
		}
		catch (Exception e) {
			log.error("packetRecieved", e);
		}
	}
/*	private final static int HEADER_LENGTH = 9;
	private final static int TAG_HEADER_LENGTH = 11;
	private final static int META_POSITION = 13;
	private IFLV flv;
	private volatile long bytesWritten;
	private int offset;
	private int fileMetaSize = 0;
	private volatile int videoCodecId = -1;
	private volatile int audioCodecId = -1;
	private boolean append;
	private int duration;
	private RandomAccessFile file;
	private volatile int previousTagSize = 0;

	private List<byte[]> metadataList = new ArrayList<byte[]>();
	
	public List<byte[]> getMetaDataList() {
		return metadataList;
	}
	
	public FlvByteManager() {
	}
	/**
	 * Header情報のバイトデータを応答する。
	 * @return
	 * /
	public byte[] getHeader() {
		// headerのバイトデータを応答する。(放送開始してからオーディオパケットを取得するか？ビデオパケットを取得するか？)
		// この２点だけ押さえる必要があるけど、タグでONになっててもデータがながれてこないならセーフかな？
		FLVHeader flvHeader = new FLVHeader();
		flvHeader.setFlagAudio(true);
		flvHeader.setFlagVideo(true);
		IoBuffer header = IoBuffer.allocate(HEADER_LENGTH + 4);
		flvHeader.write(header);
		header.flip();
		byte[] data = header.array();
		header.clear();
		// これは固定
		return data;
	}
	/**
	 * Meta情報のバイトデータを応答する。
	 * いらないかも・・・
	 * @return
	 * /
	public byte[] getMetaData() {
		// metaデータを応答する。
		// これは放送によりけり。
		IoBuffer buf = IoBuffer.allocate(192);
		buf.setAutoExpand(true);
		Output out = new Output(buf);
		out.writeString("onMetaData");
		Map<Object, Object> params = new HashMap<Object, Object>();
		params.put("server", Red5.getVersion().replaceAll("\\$", "").trim());
		params.put("creationdate", GregorianCalendar.getInstance().getTime().toString());
		params.put("duration", 3);
		System.out.println("metadata:video:" + videoCodecId);
		if(videoCodecId != -1) {
			params.put("videocodecid", videoCodecId);
		}
		else {
			params.put("novideocodec", 0);
		}
		System.out.println("metadata:audio:" + audioCodecId);
		if(audioCodecId != -1) {
			params.put("audiocodecid", audioCodecId);
		}
		else {
			params.put("noaudiocodec", 0);
		}
		params.put("canSeekToEnd", true);
		out.writeMap(params, new Serializer());
		buf.flip();
		System.out.println(buf.getHexDump());
		
		ITag onMetaData = new Tag(ITag.TYPE_METADATA, 0, buf.limit(), buf, 0);
		return getBodyData2(onMetaData);
	}
	private boolean videoflg = false;
	private boolean audioflg = false;
	/**
	 * 中間データを応答する。
	 * @return
	 * /
	public byte[] getBodyData(ITag tag) {
		// タグデータを元にflvの内容データを生成する。
		int bodySize = tag.getBodySize();
		if(bodySize > 0) {
			byte dataType = tag.getDataType();
			int totalTagSize = TAG_HEADER_LENGTH + bodySize + 4;
			IoBuffer tagBuffer = IoBuffer.allocate(totalTagSize);
			int timestamp = tag.getTimestamp();
			byte[] bodyBuf = new byte[bodySize];
//			System.out.println(tag.getBody().array().length);
			tag.getBody().get(bodyBuf);
			if(dataType == ITag.TYPE_AUDIO && audioCodecId == -1) {
				int id = bodyBuf[0] & 0xFF;
				audioCodecId = (id & ITag.MASK_SOUND_FORMAT) >> 4;
				System.out.println(audioCodecId);
			}
			else if(dataType == ITag.TYPE_VIDEO && videoCodecId == -1) {
				int id = bodyBuf[0] & 0xFF;
				videoCodecId = id & ITag.MASK_VIDEO_CODEC;
				System.out.println(videoCodecId);
			}
			tagBuffer.put(dataType); // 1
//			System.out.print(dataType);
//			System.out.print(":");
			IOUtils.writeMediumInt(tagBuffer, bodySize); // 3
//			System.out.println(timestamp);
			if(dataType == ITag.TYPE_METADATA) {
				// メタタグだけ、設置時刻を0秒にしておく。
				IOUtils.writeExtendedMediumInt(tagBuffer, 0);
			}
			else {
				// このタイムスタンプをそれぞれのFlashプレーヤーの再生時刻にあわせたデータにしなければならない。そんな気がする。
				IOUtils.writeExtendedMediumInt(tagBuffer, timestamp); // 4
			}
			IOUtils.writeMediumInt(tagBuffer, 0); // 3
			tagBuffer.put(bodyBuf);
			previousTagSize = TAG_HEADER_LENGTH + bodySize;
			tagBuffer.putInt(previousTagSize);
			tagBuffer.flip();
			byte[] data = tagBuffer.array();
			tagBuffer.clear();
			if(dataType == ITag.TYPE_METADATA) {
				metadataList.add(data);
			}
			if(!videoflg && dataType == ITag.TYPE_VIDEO) {
				metadataList.add(data);
				videoflg = true;
			}
			if(!audioflg && dataType == ITag.TYPE_AUDIO) {
				metadataList.add(data);
				audioflg = true;
			}
			return data;
		}
		return null;
	}
	public byte[] getBodyData2(ITag tag) {
		// タグデータを元にflvの内容データを生成する。
		int bodySize = tag.getBodySize();
		if(bodySize > 0) {
			byte dataType = tag.getDataType();
			int totalTagSize = TAG_HEADER_LENGTH + bodySize + 4;
			IoBuffer tagBuffer = IoBuffer.allocate(totalTagSize);
			int timestamp = tag.getTimestamp();
			byte[] bodyBuf = new byte[bodySize];
//			System.out.println(tag.getBody().array().length);
			tag.getBody().get(bodyBuf);
			if(dataType == ITag.TYPE_AUDIO && audioCodecId == -1) {
				int id = bodyBuf[0] & 0xFF;
				audioCodecId = (id & ITag.MASK_SOUND_FORMAT) >> 4;
				System.out.println(audioCodecId);
			}
			else if(dataType == ITag.TYPE_VIDEO && videoCodecId == -1) {
				int id = bodyBuf[0] & 0xFF;
				videoCodecId = id & ITag.MASK_VIDEO_CODEC;
				System.out.println(videoCodecId);
			}
			tagBuffer.put(dataType); // 1
//			System.out.print(dataType);
//			System.out.print(":");
			IOUtils.writeMediumInt(tagBuffer, bodySize); // 3
//			System.out.println(timestamp);
			// このタイムスタンプをそれぞれのFlashプレーヤーの再生時刻にあわせたデータにしなければならない。そんな気がする。
			IOUtils.writeExtendedMediumInt(tagBuffer, timestamp); // 4
			IOUtils.writeMediumInt(tagBuffer, 0); // 3
			tagBuffer.put(bodyBuf);
			previousTagSize = TAG_HEADER_LENGTH + bodySize;
			tagBuffer.putInt(previousTagSize);
			tagBuffer.flip();
			byte[] data = tagBuffer.array();
			tagBuffer.clear();
			return data;
		}
		return null;
	}
	public FlvByteManager(File file, boolean append) {
		try {
			this.file = new RandomAccessFile(file, "rws");
			this.append = append;
			init();
		}
		catch (Exception e) {
			log.error("Failed to create FLVwriter", e);
		}
	}
	private void init() {
		if(!append) {
			try {
				// Headerを書き込む。
				writeHeader();
				writeMetadataTag(0, videoCodecId, audioCodecId);
			}
			catch (Exception e) {
				log.warn("exception write header...", e);
			}
		}
	}
	@Override
	public void writeHeader() throws IOException {
		FLVHeader flvHeader = new FLVHeader();
		flvHeader.setFlagAudio(true); // 片方しかない場合は調整が必要かも？
		flvHeader.setFlagVideo(true); // 片方しかない場合は調整が必要かも？
		// create a buffer
		ByteBuffer header = ByteBuffer.allocate(HEADER_LENGTH + 4); // FLVHeader (9 bytes) + PreviousTagSize0 (4 bytes)
		flvHeader.write(header);
		// write header to output channel
		file.setLength(HEADER_LENGTH + 4);
		if (header.hasArray()) {
			log.debug("Header bytebuffer has a backing array");
			file.write(header.array());
		} else {
			log.debug("Header bytebuffer does not have a backing array");
			byte[] tmp = new byte[HEADER_LENGTH + 4];
			header.get(tmp);
			file.write(tmp);
		}
		bytesWritten = file.length();
		header.clear();
	}
	@Override
	public IStreamableFile getFile() {
		return flv;
	}
	@Override
	public void setFile(File file) {
		try {
			this.file = new RandomAccessFile(file, "rwd");
		}
		catch (Exception e) {
			log.warn("file could not be set", e);
		}
	}
	public void setFLV(IFLV flv) {
		this.flv = flv;
	}
	@Override
	public int getOffset() {
		return offset;
	}
	public void setOffset(int offset) {
		this.offset = offset;
	}
	@Override
	public long getBytesWritten() {
		return bytesWritten;
	}
	@Override
	public boolean writeTag(ITag tag) throws IOException {
		/*
		 * Tag header = 11 bytes
		 * |-|---|----|---|
		 *    0 = type
		 *  1-3 = data size
		 *  4-7 = timestamp
		 * 8-10 = stream id (always 0)
		 * Tag data = variable bytes
		 * Previous tag = 4 bytes (tag header size + tag data size)
		 * /
		log.debug("writeTag - previous size: {}", previousTagSize);
		log.trace("Tag: {}", tag);
		long prevBytesWritten = bytesWritten;
		// skip tags with no data
		int bodySize = tag.getBodySize();
		log.debug("Tag body size: {}", bodySize);
		if (bodySize > 0) {
			// ensure that the channel is still open
			if (file != null) {
				// get the data type
				byte dataType = tag.getDataType();
				// set a var holding the entire tag size including the previous tag length
				int totalTagSize = TAG_HEADER_LENGTH + bodySize + 4;
				// resize
				file.setLength(file.length() + totalTagSize);
				// create a buffer for this tag
				ByteBuffer tagBuffer = ByteBuffer.allocate(totalTagSize);
				// get the current file offset
				long fileOffset = file.getFilePointer();
				log.debug("Current file offset: {} expected offset: {}", fileOffset, prevBytesWritten);
				// if we're writing non-meta tags do seeking and tag size update
				if (dataType != ITag.TYPE_METADATA) {
					if (fileOffset < prevBytesWritten && dataType != ITag.TYPE_METADATA) {
						log.debug("Seeking to expected offset");
						// it's necessary to seek to the length of the file
						// so that we can append new tags
						file.seek(prevBytesWritten);
					}
					// dont reset previous tag size on metadata
					if (previousTagSize != tag.getPreviousTagSize()) {
						log.debug("Previous tag size: {} previous per tag: {}", previousTagSize, tag.getPreviousTagSize());
						tag.setPreviousTagSize(previousTagSize);
					}
				}
				int timestamp = tag.getTimestamp() + offset;
				// create an array big enough
				byte[] bodyBuf = new byte[bodySize];
				// put the bytes into the array
				tag.getBody().get(bodyBuf);
				// get the audio or video codec identifier
				if (dataType == ITag.TYPE_AUDIO && audioCodecId == -1) {
					int id = bodyBuf[0] & 0xff; // must be unsigned
					audioCodecId = (id & ITag.MASK_SOUND_FORMAT) >> 4;
					log.debug("Audio codec id: {}", audioCodecId);
				} else if (dataType == ITag.TYPE_VIDEO && videoCodecId == -1) {
					int id = bodyBuf[0] & 0xff; // must be unsigned
					videoCodecId = id & ITag.MASK_VIDEO_CODEC;
					log.debug("Video codec id: {}", videoCodecId);
				}
				// Data Type
				tagBuffer.put(dataType); //1
				// Body Size - Length of the message. Number of bytes after StreamID to end of tag 
				// (Equal to length of the tag - 11) 
				IOUtils.writeMediumInt(tagBuffer, bodySize); //3
				// Timestamp
				IOUtils.writeExtendedMediumInt(tagBuffer, timestamp); //4
				// Stream id
				IOUtils.writeMediumInt(tagBuffer, 0); //3
				// get the body
				tagBuffer.put(bodyBuf);
				// update previous tag size
				previousTagSize = TAG_HEADER_LENGTH + bodySize;
				// we add the tag size
				tagBuffer.putInt(previousTagSize);
				// flip so we can process from the beginning
				tagBuffer.flip();
				if (log.isDebugEnabled()) {
					//StringBuilder sb = new StringBuilder();
					//HexDump.dumpHex(sb, tagBuffer.array());
					//log.debug("\n{}", sb);
				}
				// write the tag
				file.write(tagBuffer.array());
				bytesWritten = file.length();
				log.debug("Bytes written: {} tag size: {}", bytesWritten, 4);
				tagBuffer.clear();
				// update the duration
				duration = Math.max(duration, timestamp);
				log.debug("Writer duration: {}", duration);
				// validate written amount
				if ((bytesWritten - prevBytesWritten) != (previousTagSize + 4)) {
					log.debug("Not all of the bytes appear to have been written, prev-current: {}", (bytesWritten - prevBytesWritten));
				}
			} else {
				// throw an exception and let them know the cause
				throw new IOException("FLV write channel has been closed and cannot be written to", new ClosedChannelException());
			}
		} else {
			log.debug("Empty tag skipped: {}", tag);
			return false;
		}
		return true;
	}
	@Override
	public boolean writeTag(byte arg0, IoBuffer arg1) throws IOException {
		return false;
	}
	@Override
	public void close() {
		log.debug("close");
		try {
			// keep track of where the pointer is before we update the header and meta
			long tail = bytesWritten;
			// set to where the flv header goes
			file.seek(0);
			//Header fields (in same order than spec, for comparison purposes)
			FLVHeader flvHeader = new FLVHeader();
			flvHeader.setFlagAudio(audioCodecId != -1 ? true : false);
			flvHeader.setFlagVideo(videoCodecId != -1 ? true : false);
			// create a buffer
			ByteBuffer header = ByteBuffer.allocate(HEADER_LENGTH + 4);
			flvHeader.write(header);
			// write header to output channel
			file.write(header.array());
			header.clear();
			// here we overwrite the metadata with the final duration
			// get the file offset
			long fileOffset = file.getFilePointer();
			log.debug("Current file offset: {} expected offset: {}", fileOffset, META_POSITION);
			if (fileOffset < META_POSITION) {
				file.seek(META_POSITION);
				fileOffset = file.getFilePointer();
				log.debug("Updated file offset: {} expected offset: {}", fileOffset, META_POSITION);
			}
			log.debug("In the metadata writing (close) method - duration:{}", duration);
			writeMetadataTag(duration * 0.001, videoCodecId, audioCodecId);
			// seek to the end of the data
			file.seek(tail);
		} catch (IOException e) {
			log.error("IO error on close", e);
		} finally {
			try {
				if (file != null) {
					// run a test on the flv if debugging is on
					if (log.isDebugEnabled()) {
						// debugging
						//testFLV();
					}
					// close the file
					file.close();
				}
			} catch (IOException e) {
				log.error("", e);
			}
		}
	}
	@Override
	public boolean writeStream(byte[] arg0) {
		return false;
	}
	private void writeMetadataTag(double duration, int videoCodecId, int audioCodecId) throws IOException {
		log.debug("writeMetadataTag - duration: {} video codec: {} audio codec: {}", new Object[] { duration, videoCodecId, audioCodecId });
		IoBuffer buf = IoBuffer.allocate(192);
		buf.setAutoExpand(true);
		Output out = new Output(buf);
		out.writeString("onMetaData");
		Map<Object, Object> params = new HashMap<Object, Object>();
		params.put("server", Red5.getVersion().replaceAll("\\$", "").trim());
		params.put("creationdate", GregorianCalendar.getInstance().getTime().toString());
//		params.put("duration", duration); // まずDurationをこわした状態で動作に問題がないか確認しておく。
		if (videoCodecId != -1) {
			params.put("videocodecid", videoCodecId);
		} else {
			// place holder
			params.put("novideocodec", 0);			
		}
		if (audioCodecId != -1) {
			params.put("audiocodecid", audioCodecId);
		} else {
			// place holder
			params.put("noaudiocodec", 0);			
		}
//		params.put("canSeekToEnd", true);
		params.put("canSeekToEnd", false);
		out.writeMap(params, new Serializer());
		buf.flip();
		if (fileMetaSize == 0) {
			fileMetaSize = buf.limit();
		}
		log.debug("Metadata size: {}", fileMetaSize);
		ITag onMetaData = new Tag(ITag.TYPE_METADATA, 0, fileMetaSize, buf, 0);
		writeTag(onMetaData);
	}//*/
	// 1062 / 3 = 354 - 11 343
/* 1977 / 3 = 659
32 00 00 84 2E C0 82 9F 30 13 A6 02 77 C6 BF 1A FD 19 50 B7 E8 8A C5 81 4F 98 09 D3 01 3B E3 5F 8D 7E 8C A8 5B F4 45 62 C0 A7 CC 04 E9 80 9D F1 AF C6 BF 46 54 2D FA 22 B1 60 53 E6 02 74 C0 4E F8 D7 E3 5F A3 2A 16 FD 11 58 B0 29 F3 01 3A 60 27 7C 6B F1 AF D1 95 0B 7E 88 AC 58 14 F9 80 9D 30 13 BE 35 F8 D7 E8 CA 85 BF 44 56 2C 0A 7C C0 4E 98 09 DF 1A FC 6B F4 65 42 DF A2 2B 16 05 3E 60 27 4C 04 EF 8D 7E 35 FA 32 A1 6F D1 15 8B 02 9F 30 13 A6 02 77 C6 BF 1A FD 19 50 B7 E8 8A C5 81 4F 98 09 D3 01 3B E3 5F 8D 7E 8C A8 5B F4 45 62 C0 A7 CC 04 E9 80 9D F1 AF C6 BF 46 54 2D FA 22 B1 60 53 E6 02 74 C0 4E F8 D7 E3 5F A3 2A 16 FD 11 58 B0 29 F3 01 3A 60 27 7C 6B F1 AF D1 95 0B 7E 88 AC 58 14 F9 80 9D 30 13 BE 35 F8 D7 E8 CA 85 BF 44 56 2C 0A 7C C0 4E 98 09 DF 1A FC 6B F4 65 42 DF A2 2B 16 05 3E 60 27 4C 04 EF 8D 7E 35 FA 32 A1 6F D1 15 8B 02 9F 30 13 A6 02 77 C6 BF 1A FD 19 50 B7 E8 8A C5 81 4F 98 09 D3 01 3B E3 5F 8D 7E 8C A8 5B F4 45 62 C0 A7 CC 04 E9 80 9D F1 AF C6 BF 46 54 2D FA 22 B1 60 53 E6 02 74 C0 4E F8 D7 E3 5F A3 2A 16 FD 11 58 B3 FF FF FF FF FF FF FF D4 D5 F4 99 5F 49 94 D5 F4 99 5F 49 94 D5 F4 99 5F 49 94 D5 F4 99 5F 49 94 D5 F4 99 5F 49 94 D5 F4 99 5F 49 94 D5 F4 99 5F 49 82 8B 2A 0C 55 F4 98 18 DF F0 EC A0 ED 2D 65 70 F4 D0 83 01 6A 14 F1 00 11 52 39 20 7C 1A 8D 41 8D FF C0 46 D3 9F 16 D0 64 00 40 4D 65 28 4A 14 F4 45 03 80 FC FE C4 4D 87 40 3E 87 41 FA 02 C3 8C 20 A0 B0 D8 90 15 C2 80 56 8B 82 9F 44 18 B6 8A AE 83 2A FE 45 C0 56 87 98 6A 02 B5 BD 38 88 1B 6F F1 0C 6D A1 E2 53 A1 4F EA FA 4B 62 E2 E0 64 80 49 41 91 81 E2 51 93 05 A0 C8 3F 93 B4 D0 31 C0 25 88 C9 54 D5 F4 99 5F 49 94 D5 F4 99 5F 49 94 D5 F4 99 5F 49 94 D5 F4 99 5F 49 94 D5 F4 99 5F 49 94 D5 F4 99 5F 49 94 D5 F4 99 5F 49 94 D5 F4 99 5F 49 81 BB 78 BD E2 F7 8B DE 2F 78 BD E2 F7 8B DF 8B DE 2F 78 BD E2 F7 8B DE 2F 78 BD E2 F7 8B DE 2F 7F FF FF FF 5B 7F EC 6F FF FE C6 FF F6 F7 F8 FD FF FF D8 DF FF FF 17 BF FF FF FF F6 37 E2 F7 FF 8F DE 3F 78 FD ED EF FF E3 F7 8F DE 3F 7F FF FF
*/
}
