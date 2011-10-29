package com.ttProject.packetTest.flvbyte;

/**
 * 生成したFlvパケット(送信対象)を処理するためのリスナー
 * @author taktod
 */
public interface IFlvByteListener {
	/**
	 * 初期データが変更されたときのイベント
	 */
	public void changeInitDataEvent();
	/**
	 * streamEventで生成されたデータイベント
	 * @param data
	 */
	public void packetEvent(byte[] data);
}
