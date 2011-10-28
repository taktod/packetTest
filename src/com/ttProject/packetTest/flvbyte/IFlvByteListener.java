package com.ttProject.packetTest.flvbyte;

/**
 * 生成したFlvパケット(送信対象)を処理するためのリスナー
 * @author taktod
 */
public interface IFlvByteListener {
	/**
	 * streamEventで生成されたデータイベント
	 * @param data
	 */
	public void packetCreated(byte[] data);
}
