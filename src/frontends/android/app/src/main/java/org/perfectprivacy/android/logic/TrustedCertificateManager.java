/*
 * Copyright (C) 2012-2015 Tobias Brunner
 * Copyright (C) 2012 Giuliano Grassi
 * Copyright (C) 2012 Ralf Sager
 * HSR Hochschule fuer Technik Rapperswil
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.  See <http://www.fsf.org/copyleft/gpl.txt>.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * for more details.
 */

package org.perfectprivacy.android.logic;

import android.util.Log;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Observable;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class TrustedCertificateManager extends Observable
{
	private static final String TAG = TrustedCertificateManager.class.getSimpleName();
	private final ReentrantReadWriteLock mLock = new ReentrantReadWriteLock();
	private Hashtable<String, X509Certificate> mCACerts = new Hashtable<String, X509Certificate>();
	private volatile boolean mReload;
	private boolean mLoaded;
	private final ArrayList<KeyStore> mKeyStores = new ArrayList<KeyStore>();

	public enum TrustedCertificateSource
	{
		SYSTEM("system:"),
		USER("user:"),
		LOCAL("local:");

		private final String mPrefix;

		private TrustedCertificateSource(String prefix)
		{
			mPrefix = prefix;
		}

		private String getPrefix()
		{
			return mPrefix;
		}
	}

	/**
	 * Private constructor to prevent instantiation from other classes.
	 */
	private TrustedCertificateManager()
	{
		for (String name : new String[]{"LocalCertificateStore", "AndroidCAStore"})
		{
			KeyStore store;
			try
			{
				store = KeyStore.getInstance(name);
				store.load(null, null);
				mKeyStores.add(store);
			}
			catch (Exception e)
			{
				Log.e(TAG, "Unable to load KeyStore: " + name);
				e.printStackTrace();
			}
		}
	}

	/**
	 * This is not instantiated until the first call to getInstance()
	 */
	private static class Singleton
	{
		public static final TrustedCertificateManager mInstance = new TrustedCertificateManager();
	}

	/**
	 * Get the single instance of the CA certificate manager.
	 *
	 * @return CA certificate manager
	 */
	public static TrustedCertificateManager getInstance()
	{
		return Singleton.mInstance;
	}

	/**
	 * Invalidates the current load state so that the next call to load()
	 * will force a reload of the cached CA certificates.
	 *
	 * Observers are notified when this method is called.
	 *
	 * @return reference to itself
	 */
	public TrustedCertificateManager reset()
	{
		Log.d(TAG, "Force reload of cached CA certificates on next load");
		this.mReload = true;
		this.setChanged();
		this.notifyObservers();
		return this;
	}

	/**
	 * Ensures that the certificates are loaded but does not force a reload.
	 * As this takes a while if the certificates are not loaded yet it should
	 * be called asynchronously.
	 *
	 * Observers are only notified when the certificates are initially loaded, not when reloaded.
	 *
	 * @return reference to itself
	 */
	public TrustedCertificateManager load()
	{
		Log.d(TAG, "Ensure cached CA certificates are loaded");
		this.mLock.writeLock().lock();
		if (!this.mLoaded || this.mReload)
		{
			this.mReload = false;
			loadCertificates();
		}
		this.mLock.writeLock().unlock();
		return this;
	}

	/**
	 * Opens the CA certificate KeyStore and loads the cached certificates.
	 * The lock must be locked when calling this method.
	 */
	private void loadCertificates()
	{
		Log.d(TAG, "Load cached CA certificates");
		Hashtable<String, X509Certificate> certs = new Hashtable<String, X509Certificate>();
		for (KeyStore store : this.mKeyStores)
		{
			fetchCertificates(certs, store);
		}
		this.mCACerts = certs;
		if (!this.mLoaded)
		{
			this.setChanged();
			this.notifyObservers();
			this.mLoaded = true;
		}
		Log.d(TAG, "Cached CA certificates loaded");
	}

	/**
	 * Load all X.509 certificates from the given KeyStore.
	 *
	 * @param certs Hashtable to store certificates in
	 * @param store KeyStore to load certificates from
	 */
	private void fetchCertificates(Hashtable<String, X509Certificate> certs, KeyStore store)
	{

		//Perfect Privacy CA CERT (Stand 02.02.2016)
		String crt = "-----BEGIN CERTIFICATE-----\n" +
				"MIIHOjCCBSKgAwIBAgIJAOgzuXGrNVWFMA0GCSqGSIb3DQEBDQUAMIGQMQswCQYD\n" +
				"VQQGEwJDSDEMMAoGA1UECBMDWnVnMQwwCgYDVQQHEwNadWcxGDAWBgNVBAoTD1Bl\n" +
				"cmZlY3QgUHJpdmFjeTEhMB8GA1UEAxMYUGVyZmVjdCBQcml2YWN5IElQU0VDIENB\n" +
				"MSgwJgYJKoZIhvcNAQkBFhlhZG1pbkBwZXJmZWN0LXByaXZhY3kuY29tMB4XDTE2\n" +
				"MDQxMTIzMjU1NVoXDTI2MDQwOTIzMjU1NVowgZAxCzAJBgNVBAYTAkNIMQwwCgYD\n" +
				"VQQIEwNadWcxDDAKBgNVBAcTA1p1ZzEYMBYGA1UEChMPUGVyZmVjdCBQcml2YWN5\n" +
				"MSEwHwYDVQQDExhQZXJmZWN0IFByaXZhY3kgSVBTRUMgQ0ExKDAmBgkqhkiG9w0B\n" +
				"CQEWGWFkbWluQHBlcmZlY3QtcHJpdmFjeS5jb20wggIiMA0GCSqGSIb3DQEBAQUA\n" +
				"A4ICDwAwggIKAoICAQDHcIewTlU9SJN7GW7mUQpSkvPc6M8aOxrbUrgCEojLLgwX\n" +
				"GNT3QhGmB3NUc+erR1RXF6T2XsPR9xtBUPKeSogyR4ifKXB8SlL+9MP45THw8fCw\n" +
				"9qeQEy0eqMdtGU9R8K1wgbm9jstaDKkSBoU9zTQMo+kDbHP7190JyfeC5uG3u//A\n" +
				"NPpwDs4MTHWEphiybZIt3z06ClunFNjheWTJZNSMckxCq/L+nEihUcrgtwf72+Lj\n" +
				"fAoBtGRb0mp3PjoAfG9q46gwS19gwOeaK3Kq35tNFmsmr6gzCIbJiGtc9iqpgM7N\n" +
				"1bEAxhkb9EkUC63gas4fZjqKv8e1H/KNwqRPK9TUyVMG1unaVUZcg4Mqxz4mOwRf\n" +
				"HSVFsaHlh+Ss9CdY3b2vRwxvcm/g1JgjynSvm3GAE4QIEnVSko/cQyU0EfvFuFhF\n" +
				"obPTUzKLLRtofn/RmWC9coYlvleLX23xOxzf5oCNym5i7ZecubeBGjsE7WMPaBaE\n" +
				"l0I+D2xBwouMIiRYwAL1+tAAhyHsNheSrTeWKRj8Zy2gbI4aeCs4JZC15UINLtJD\n" +
				"QhkfeBENRSyhKqATbYDnT1Uq3ju+xcDAcm9cavJH4iThWskmFtAxuzjNPTnJafaX\n" +
				"YTyc7z59rWGG7RcSgLcMI1DyUN8P1ARx+ERFArdXim0jz9HIHlngACVpMctiZQID\n" +
				"AQABo4IBkzCCAY8wHQYDVR0OBBYEFFzN7fVJPhS9C4MtrgSxbAo4am7vMIHFBgNV\n" +
				"HSMEgb0wgbqAFFzN7fVJPhS9C4MtrgSxbAo4am7voYGWpIGTMIGQMQswCQYDVQQG\n" +
				"EwJDSDEMMAoGA1UECBMDWnVnMQwwCgYDVQQHEwNadWcxGDAWBgNVBAoTD1BlcmZl\n" +
				"Y3QgUHJpdmFjeTEhMB8GA1UEAxMYUGVyZmVjdCBQcml2YWN5IElQU0VDIENBMSgw\n" +
				"JgYJKoZIhvcNAQkBFhlhZG1pbkBwZXJmZWN0LXByaXZhY3kuY29tggkA6DO5cas1\n" +
				"VYUwDAYDVR0TBAUwAwEB/zBKBglghkgBhvhCAQQEPRY7aHR0cDovL3d3dy5wZXJm\n" +
				"ZWN0LXByaXZhY3kuY29tL1BlcmZlY3RfUHJpdmFjeV9JUFNFQ19DQS5jcmwwTAYD\n" +
				"VR0fBEUwQzBBoD+gPYY7aHR0cDovL3d3dy5wZXJmZWN0LXByaXZhY3kuY29tL1Bl\n" +
				"cmZlY3RfUHJpdmFjeV9JUFNFQ19DQS5jcmwwDQYJKoZIhvcNAQENBQADggIBAGCr\n" +
				"XDVQJxyN0w0CUPk1zqiq98uSR6Cm1xeixbdrU0z1VYcNWj0LSgbbju/XNyT+8zYu\n" +
				"B+BodYyYKzFLLIuvzkYMecXXglCV5uwKQ9tzFv8GxRZruAw7Z7bcXxBDyayzrILt\n" +
				"P2oe0Ljpoj0NnnuTbpqiYLpQfBQtdS5YJ+QSsKZUC7b+hFqbfy++LT/IkwAQciFT\n" +
				"m2BkA9g7ObuvkufqNg0puRPhrIMsajQrLuU7AvAw63FZFyvM/4wvuwsA4O+6DGp8\n" +
				"dUTqA3UynNRkQVeUxeZvqqsvbukGD95rXK3c+Li6Ftib5Usx/sQx9JS45Uk4n0AI\n" +
				"yDUirsLudEkNPn8rpebFX65GAZC7hvys2wBFEbEKJIXBvLQiLy/XeAct0opylyBh\n" +
				"sqipqKVyDXX2wvQRan6yhBnZsf9v/vr00ybdenYl1Vb0FKX2BLGU8VdydgbKr8gX\n" +
				"dFT+oBpXp4Yet559UIvkUQE7bBt8X9H/VcTm73AvO5stquM4zcPZ4vR7/Btj7pfO\n" +
				"r9qLJB26CcI1LdXO39/NmYcjAjSNbJVbpI4gnFJHK9YlITGBrLR2yhRtHdZD9zMW\n" +
				"1kMdOaxxKnDMZHDOgJlvNaK7qMXSst04gRkGySIgUwHPPH85g5el4h1LP8i3EeXa\n" +
				"2g2eH8ETgGeGN/j6D4CzmewtOi3BkqLdGSPma1Tz\n" +
				"-----END CERTIFICATE-----";

		try {
			CertificateFactory factory = CertificateFactory.getInstance("X.509");
			X509Certificate certificate = (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(crt.getBytes()));
			certs.put("user:ppca", certificate);
		} catch (CertificateException e) {
			e.printStackTrace();
		}

		return;
	}

	/**
	 * Retrieve the CA certificate with the given alias.
	 *
	 * @param alias alias of the certificate to get
	 * @return the certificate, null if not found
	 */
	public X509Certificate getCACertificateFromAlias(String alias)
	{
		X509Certificate certificate = null;

		if (this.mLock.readLock().tryLock())
		{
			certificate = this.mCACerts.get(alias);
			this.mLock.readLock().unlock();
		}
		else
		{	/* if we cannot get the lock load it directly from the KeyStore,
			 * should be fast for a single certificate */
			for (KeyStore store : this.mKeyStores)
			{
				try
				{
					Certificate cert = store.getCertificate(alias);
					if (cert != null && cert instanceof X509Certificate)
					{
						certificate = (X509Certificate)cert;
						break;
					}
				}
				catch (KeyStoreException e)
				{
					e.printStackTrace();
				}
			}
		}
		return certificate;
	}

	/**
	 * Get all CA certificates (from all keystores).
	 *
	 * @return Hashtable mapping aliases to certificates
	 */
	@SuppressWarnings("unchecked")
	public Hashtable<String, X509Certificate> getAllCACertificates()
	{
		Hashtable<String, X509Certificate> certs;
		this.mLock.readLock().lock();
		certs = (Hashtable<String, X509Certificate>)this.mCACerts.clone();
		this.mLock.readLock().unlock();
		return certs;
	}

	/**
	 * Get all certificates from the given source.
	 *
	 * @param source type to filter certificates
	 * @return Hashtable mapping aliases to certificates
	 */
	public Hashtable<String, X509Certificate> getCACertificates(TrustedCertificateSource source)
	{
		Hashtable<String, X509Certificate> certs = new Hashtable<String, X509Certificate>();
		this.mLock.readLock().lock();
		for (String alias : this.mCACerts.keySet())
		{
			if (alias.startsWith(source.getPrefix()))
			{
				certs.put(alias, this.mCACerts.get(alias));
			}
		}
		this.mLock.readLock().unlock();
		return certs;
	}
}
