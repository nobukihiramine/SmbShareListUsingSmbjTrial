package com.hiramine.smbsharelistusingsmbjtrial;

import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.hierynomus.mserref.NtStatus;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.Share;
import com.rapid7.client.dcerpc.mssrvs.ServerService;
import com.rapid7.client.dcerpc.mssrvs.dto.NetShareInfo1;
import com.rapid7.client.dcerpc.transport.RPCTransport;
import com.rapid7.client.dcerpc.transport.SMBTransportFactories;

import java.io.IOException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import androidx.annotation.Nullable;

public class FileEnumerator
{
	// 定数
	private static final String LOGTAG = "FileEnumerator";

	public static final int RESULT_SUCCEEDED                    = 0;
	public static final int RESULT_FAILED_UNKNOWN_HOST          = 1;
	public static final int RESULT_FAILED_NO_ROUTE_TO_HOST      = 2;
	public static final int RESULT_FAILED_LOGON_FAILURE         = 3;
	public static final int RESULT_FAILED_BAD_NETWORK_NAME      = 4;
	public static final int RESULT_FAILED_NOT_FOUND             = 5;
	public static final int RESULT_FAILED_NOT_A_DIRECTORY       = 6;
	public static final int RESULT_FAILED_FUNCTION_AUTHENTICATE = 11;
	public static final int RESULT_FAILED_FUNCTION_GETSHARES1   = 13;
	public static final int RESULT_FAILED_UNKNOWN               = 99;

	// スレッドの作成と開始
	public void startEnumeration( Handler handler,
								  String strTargetPath,
								  String strUsername,
								  String strPassword )
	{
		Thread thread = new Thread( () -> threadfuncEnumerate( handler,
															   strTargetPath,
															   strUsername,
															   strPassword ) );
		thread.start();
	}

	// スレッド関数
	private void threadfuncEnumerate( Handler handler,
									  String strTargetPath,
									  String strUsername,
									  String strPassword )
	{
		Log.d( LOGTAG, "Enumeration thread started." );

		// TargetPathから、HostName,ShareName,Pathを切り出す。
		// smb://hostname/sharename/directory1/directory2/filename
		Uri    uri         = Uri.parse( strTargetPath );
		String strHostName = uri.getHost();    // HostNameの切り出し

		String strDomain = "";    // Domainとして、空文字の他、"WORKGROUP"や適当な文字列など、何を指定しても特に動作変化見られず。

		// 呼び出し元スレッドに返却する用のメッセージ変数の取得
		Message message = Message.obtain( handler );

		try
		{
			// ServerServiceの作成
			SmbConfig  smbconfig = SmbConfig.createDefaultConfig();
			SMBClient  smbclient = new SMBClient( smbconfig );
			Connection connection;
			try
			{
				connection = smbclient.connect( strHostName );
			}
			catch( UnknownHostException e )
			{    // ホストが存在しないか、名前解決できない
				message.what = RESULT_FAILED_UNKNOWN_HOST;
				message.obj = null;
				Log.w( LOGTAG, "Enumeration thread end. : Unknown host." );
				return;    // ※注）関数を抜ける前にfinallyの処理が実行される。
			}
			catch( NoRouteToHostException e )
			{    // ホストへのルートがない
				message.what = RESULT_FAILED_NO_ROUTE_TO_HOST;
				message.obj = null;
				Log.w( LOGTAG, "Enumeration thread end. : No route to host." );
				return;    // ※注）関数を抜ける前にfinallyの処理が実行される。
			}
			AuthenticationContext authenticationcontext = new AuthenticationContext( strUsername,
																					 strPassword.toCharArray(),
																					 strDomain );
			Session session;
			try
			{
				session = connection.authenticate( authenticationcontext );
			}
			catch( SMBApiException e )
			{
				if( NtStatus.STATUS_LOGON_FAILURE == e.getStatus() )
				{    // Connection#authenticate()の結果「Logon failure」
					message.what = RESULT_FAILED_LOGON_FAILURE;
					message.obj = null;
					Log.w( LOGTAG, "Enumeration thread end. : Logon failure." );
					return;    // ※注）関数を抜ける前にfinallyの処理が実行される。
				}
				else
				{    // Connection#authenticate()の結果「Logon failure」以外で失敗
					message.what = RESULT_FAILED_FUNCTION_AUTHENTICATE;
					message.obj = null;
					Log.e( LOGTAG, "Enumeration thread end. : Function authenticate() failed." );
					return;    // ※注）関数を抜ける前にfinallyの処理が実行される。
				}
			}
			RPCTransport  transport     = SMBTransportFactories.SRVSVC.getTransport( session );
			ServerService serverservice = new ServerService( transport );

			// 列挙
			List<NetShareInfo1> listNetShareInfo1;
			try
			{
				listNetShareInfo1 = serverservice.getShares1();
			}
			catch( IOException e )
			{
				// ServerService#getShares1()の結果、原因不明で失敗
				message.what = RESULT_FAILED_FUNCTION_GETSHARES1;
				message.obj = null;
				Log.e( LOGTAG, "Enumeration thread end. : Function getShares1() failed." );
				return;    // ※注）関数を抜ける前にfinallyの処理が実行される。
			}

			// SmbFileの配列を、FileItemのリストに変換
			List<FileItem> listFileItem = makeFileItemList( listNetShareInfo1, session );

			// FileItemリストのソート
			sortFileItemList( listFileItem );

			// 成功
			message.what = RESULT_SUCCEEDED;
			message.obj = listFileItem;
			Log.d( LOGTAG, "Enumeration thread end. : Succeeded." );
		}
		catch( Exception e )
		{    // その他の失敗
			message.what = RESULT_FAILED_UNKNOWN;
			message.obj = e.getMessage();
			Log.e( LOGTAG, "Enumeration thread end. : Failed with unknown cause." );
		}
		finally
		{
			// 呼び出し元スレッドにメッセージ返却
			handler.sendMessage( message );
		}
	}

	// SmbFileの配列を、FileItemのリストに変換
	private static List<FileItem> makeFileItemList( @Nullable List<NetShareInfo1> listNetShareInfo1, Session session )
	{
		if( null == listNetShareInfo1 )
		{    // nullの場合は、空のリストを返す。
			return new ArrayList<>();
		}

		List<FileItem> listFileItem = new ArrayList<>( listNetShareInfo1.size() );    // 数が多い場合も想定し、初めに領域確保。
		for( NetShareInfo1 netshareinfo1 : listNetShareInfo1 )
		{
			/* type : https://docs.microsoft.com/en-us/openspecs/windows_protocols/ms-rap/d1c8b145-7361-4e61-836c-f9b06502961a
			NetShareInfo1 Data Structure
			Type (2 bytes): A 16-bit unsigned integer that specifies the type of the share. The Type field has the following possible values.
			0x0000 : Disk directory tree
			0x0001 : Printer queue
			0x0002 : Communications device
			0x0003 : Interprocess communication (IPC) */
			if( 0 != netshareinfo1.getType() )
			{	// Disk directory tree 以外はスキップ
				Log.w( LOGTAG, "Not disk directory tree. : " + netshareinfo1.getNetName() );
				continue;
			}

			try
			{
				Share share = session.connectShare( netshareinfo1.getNetName() );
				if( share instanceof DiskShare )
				{	// アクセス可能なディレクトリーのみ列挙（プリンタードライバーリソースやアクセス拒否フォルダは列挙しない）
					listFileItem.add( createFileItem( netshareinfo1 ) );
				}
			}
			catch( SMBApiException e)
			{
				if( NtStatus.STATUS_ACCESS_DENIED == e.getStatus() )
				{	// アクセス拒否
					Log.w( LOGTAG, "Access denied. : " + netshareinfo1.getNetName() );
				}
				else
				{	// アクセス拒否以外
					Log.w( LOGTAG, "Failed to connectShare(). : " + netshareinfo1.getNetName() );
					e.printStackTrace();
				}
			}
		}
		return listFileItem;
	}

	// FileIdBothDirectoryInformationデータから、FileItemデータの作成
	public static FileItem createFileItem( NetShareInfo1 netshareinfo1 )
	{
		FileItem.Type type          = FileItem.Type.SHARE;
		long          lLastModified = 0;
		long          lFileSize     = 0;

		return new FileItem( netshareinfo1.getNetName(),
							 netshareinfo1.getNetName(),
							 type,
							 lLastModified,
							 lFileSize );
	}

	// FileItemリストのソート
	public void sortFileItemList( List<FileItem> listFileItem )
	{
		Collections.sort( listFileItem, new FileItem.FileItemComparator() );
	}
}
