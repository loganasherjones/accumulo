/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.server.security.handler;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.NamespacePermission;
import org.apache.accumulo.core.security.SystemPermission;
import org.apache.accumulo.core.security.TablePermission;
import org.apache.accumulo.core.util.cache.Caches;
import org.apache.accumulo.core.util.cache.Caches.CacheName;
import org.apache.commons.codec.digest.Crypt;
import org.apache.hadoop.io.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.google.common.annotations.VisibleForTesting;

/**
 * All the static too methods used for this class, so that we can separate out stuff that isn't
 * using ZooKeeper. That way, we can check the synchronization model more easily, as we only need to
 * check to make sure zooCache is cleared when things are written to ZooKeeper in methods that might
 * use it. These won't, and so don't need to be checked.
 */
class ZKSecurityTool {
  private static final Logger log = LoggerFactory.getLogger(ZKSecurityTool.class);

  public static byte[] createPass(byte[] password) throws AccumuloException {
    // we rely on default algorithm and salt length (SHA-512 and 8 bytes)
    String cryptHash = Crypt.crypt(password);
    return cryptHash.getBytes(UTF_8);
  }

  private static final Cache<Text,
      String> CRYPT_PASSWORD_CACHE = Caches.getInstance()
          .createNewBuilder(CacheName.CRYPT_PASSWORDS, true).scheduler(Scheduler.systemScheduler())
          .expireAfterAccess(Duration.ofMinutes(1)).initialCapacity(4).maximumSize(64).build();

  // This uses a cache to avoid repeated expensive calls to Crypt.crypt for recent inputs
  public static boolean checkCryptPass(final byte[] password, final byte[] cryptHashInZkToTest) {
    final Text key = new Text(password);
    key.append(cryptHashInZkToTest, 0, cryptHashInZkToTest.length);
    String cachedCryptHash = CRYPT_PASSWORD_CACHE.getIfPresent(key);
    if (cachedCryptHash != null) {
      if (MessageDigest.isEqual(cryptHashInZkToTest, cachedCryptHash.getBytes(UTF_8))) {
        // If matches then zkData has not changed from when it was put into the cache
        return true;
      } else {
        // remove the non-matching entry from the cache
        CRYPT_PASSWORD_CACHE.invalidate(key);
      }
    }
    // Either !matches or was not cached
    String cryptHashToCache;
    try {
      cryptHashToCache = Crypt.crypt(password, new String(cryptHashInZkToTest, UTF_8));
    } catch (IllegalArgumentException e) {
      log.error("Unrecognized hash format", e);
      return false;
    }
    boolean matches = MessageDigest.isEqual(cryptHashInZkToTest, cryptHashToCache.getBytes(UTF_8));
    if (matches) {
      CRYPT_PASSWORD_CACHE.put(key, cryptHashToCache);
    }
    return matches;
  }

  @VisibleForTesting
  static long getCryptPasswordCacheSize() {
    CRYPT_PASSWORD_CACHE.cleanUp();
    return CRYPT_PASSWORD_CACHE.estimatedSize();
  }

  @VisibleForTesting
  static void clearCryptPasswordCache() {
    CRYPT_PASSWORD_CACHE.invalidateAll();
  }

  public static Authorizations convertAuthorizations(byte[] authorizations) {
    return new Authorizations(authorizations);
  }

  public static byte[] convertAuthorizations(Authorizations authorizations) {
    return authorizations.getAuthorizationsArray();
  }

  public static byte[] convertSystemPermissions(Set<SystemPermission> systempermissions) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(systempermissions.size());
    DataOutputStream out = new DataOutputStream(bytes);
    try {
      for (SystemPermission sp : systempermissions) {
        out.writeByte(sp.getId());
      }
    } catch (IOException e) {
      log.error("{}", e.getMessage(), e);
      // this is impossible with ByteArrayOutputStream; crash hard if this happens
      throw new UncheckedIOException(e);
    }
    return bytes.toByteArray();
  }

  public static Set<SystemPermission> convertSystemPermissions(byte[] systempermissions) {
    ByteArrayInputStream bytes = new ByteArrayInputStream(systempermissions);
    DataInputStream in = new DataInputStream(bytes);
    Set<SystemPermission> toReturn = new HashSet<>();
    try {
      while (in.available() > 0) {
        toReturn.add(SystemPermission.getPermissionById(in.readByte()));
      }
    } catch (IOException e) {
      log.error("User database is corrupt; error converting system permissions", e);
      toReturn.clear();
    }
    return toReturn;
  }

  public static byte[] convertTablePermissions(Set<TablePermission> tablepermissions) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(tablepermissions.size());
    DataOutputStream out = new DataOutputStream(bytes);
    try {
      for (TablePermission tp : tablepermissions) {
        out.writeByte(tp.getId());
      }
    } catch (IOException e) {
      log.error("{}", e.getMessage(), e);
      // this is impossible with ByteArrayOutputStream; crash hard if this happens
      throw new UncheckedIOException(e);
    }
    return bytes.toByteArray();
  }

  public static Set<TablePermission> convertTablePermissions(byte[] tablepermissions) {
    Set<TablePermission> toReturn = new HashSet<>();
    for (byte b : tablepermissions) {
      toReturn.add(TablePermission.getPermissionById(b));
    }
    return toReturn;
  }

  public static byte[] convertNamespacePermissions(Set<NamespacePermission> namespacepermissions) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(namespacepermissions.size());
    DataOutputStream out = new DataOutputStream(bytes);
    try {
      for (NamespacePermission tnp : namespacepermissions) {
        out.writeByte(tnp.getId());
      }
    } catch (IOException e) {
      log.error("{}", e.getMessage(), e);
      // this is impossible with ByteArrayOutputStream; crash hard if this happens
      throw new UncheckedIOException(e);
    }
    return bytes.toByteArray();
  }

  public static Set<NamespacePermission> convertNamespacePermissions(byte[] namespacepermissions) {
    Set<NamespacePermission> toReturn = new HashSet<>();
    for (byte b : namespacepermissions) {
      toReturn.add(NamespacePermission.getPermissionById(b));
    }
    return toReturn;
  }

}
