/*
 *  Nimble, an extensive application base for Grails
 *  Copyright (C) 2009 Intient Pty Ltd
 *
 *  Open Source Use - GNU Affero General Public License, version 3
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 *  Commercial/Private Use
 *
 *  You may purchase a commercial version of this software which
 *  frees you from all restrictions of the AGPL by visiting
 *  http://intient.com/products/nimble/licenses
 *
 *  If you have purchased a commercial version of this software it is licensed
 *  to you under the terms of your agreement made with Intient Pty Ltd.
 */
package intient.nimble.service

import org.openid4java.discovery.DiscoveryInformation
import org.openid4java.message.AuthRequest
import org.openid4java.message.ax.FetchRequest
import org.openid4java.OpenIDException
import org.openid4java.consumer.ConsumerManager
import org.openid4java.server.RealmVerifier
import org.openid4java.consumer.VerificationResult
import org.openid4java.discovery.Identifier
import org.openid4java.message.AuthSuccess
import org.openid4java.message.ax.FetchResponse
import org.openid4java.message.ax.AxMessage
import intient.nimble.auth.OpenIDToken
import intient.nimble.domain.FederationProvider

/**
 * Provides methods for interacting with OpenID authentication processes.
 *
 * @author Bradley Beddoes
 */
class OpenIDService {

  ConsumerManager manager

  boolean transactional = true

  public static final federationProviderUid = "openid"
  public static final federationProviderDiscriminator = ":openid"

  public static final yahooDiscoveryService = "yahoo:discovery"
  public static final googleDiscoveryService = "google:discovery"
  public static final flickrDiscoveryService = "flickr:discovery"

  OpenIDService() {
    manager = new ConsumerManager()


    RealmVerifier rv = new RealmVerifier()
    rv.setEnforceRpId(false)
    manager.setRealmVerifier(rv)
  }

  /**
   * Establishes an OpenID request for an OpenID service who has a pre-published identity endpoint.
   * These are generally OpenID providers say Google whom don't require users to enter their ID before continuing.
   *
   * @param service A service NAME which has been configured by openid.discovery.NAME in Nimble Apps config environment
   * @param responseUrl The Url which the openID service should respond to
   *
   * @return A twin(tuple) containing org.openid4java.discovery.DiscoveryInformation and org.openid4java.message.AuthRequest for this OpenID auth attempt
   *         Will return nulls on error.
   */
  def establishDiscoveryRequest(def service, def responseUrl) {

    def openIDFederationProvider = FederationProvider.findByUid(OpenIDService.federationProviderUid)
    if (openIDFederationProvider) {
      return establishRequest(openIDFederationProvider.preferences.get(service + ":discovery"), responseUrl)
    }
    else {
      log.warn("Unable to complete openID setup on discovery service ${service}, OpenID federation provider not located")
      log.debug e.printStackTrace()
      return [null, null]
    }
  }

  /**
   * Establishes an OpenID request for any OpenID service, user or Nimble has provided discovery endpoint.
   *
   * @param discoveryID The openID endpoint supplied by the user to discover a service at.
   * @param responseUrl The Url which the openID service should respond to.
   *
   * @return A twin(tuple) containing org.openid4java.discovery.DiscoveryInformation and org.openid4java.message.AuthRequest for this OpenID auth attempt
   *         Will return nulls on error. 
   */
  def establishRequest(def discoveryID, def responseUrl) {
    try {

      log.debug("Attempting to establish OpenID request for $discoveryID with response directed to $responseUrl")

      List discoveries = manager.discover(discoveryID)
      DiscoveryInformation discovered = manager.associate(discoveries)

      if (!discovered) {
        log.warn("Unable to complete openID setup on ${discoveryID} - discovery failed for supplied endpoint")
        return [null, null]
      }

      AuthRequest authReq = manager.authenticate(discovered, responseUrl)

      FetchRequest fetch = FetchRequest.createFetchRequest()

      fetch.addAttribute("Full name", "http://schema.openid.net/contact/namePerson", false)
      fetch.addAttribute("Alias", "http://schema.openid.net/contact/friendly", false)
      fetch.addAttribute("Email", "http://schema.openid.net/contact/email", true)
      fetch.addAttribute("Gender", "http://schema.openid.net/contact/gender", false)

      authReq.addExtension(fetch)

      log.debug("Discovered OpenID endpoint and created auth request successfully for $discoveryID")
      return [discovered, authReq]
    }
    catch (OpenIDException e) {
      log.warn("Unable to complete openID setup on ${discoveryID} - " + e.getLocalizedMessage())
      log.debug e.printStackTrace()
      return [null, null]
    }
  }

  /**
   * Processes an OpenID response to authenticate a user and extract personal information
   *
   * @param discovered The discovery object returned by the establish methods
   * @param response A populated org.openid4java.message.ParameterList associated with the response
   * @param receivingUrl The exact Url request ncluding parameters the OpenID IDP directed the user to locally
   *
   * @return A populated intient.nimble.auth.OpenIDToken or null on error
   */
  def processResponse(def discovered, def response, def receivingUrl) {

    try {
      log.debug("Verifying OpenID authentication response at $receivingUrl")
      VerificationResult verification = manager.verify(receivingUrl, response, discovered)

      Identifier identifier = verification.getVerifiedId()
      if (identifier != null) {
        OpenIDToken authToken = new OpenIDToken(identifier)
        log.debug("Created identifier $authToken.userID from $receivingUrl")

        AuthSuccess authSuccess = (AuthSuccess) verification.getAuthResponse()

        if (authSuccess.hasExtension(AxMessage.OPENID_NS_AX)) {
          FetchResponse fetchResp = (FetchResponse) authSuccess.getExtension(AxMessage.OPENID_NS_AX)

          authToken.fullName = fetchResp.getAttributeValue("Full name")
          authToken.nickName = fetchResp.getAttributeValue("Alias")
          authToken.email = fetchResp.getAttributeValue("Email")
          authToken.gender = fetchResp.getAttributeValue("Gender")

          log.debug("""$authToken.userID supplied the following identity data \n Full Name: $authToken.fullName \n Nickname: $authToken.nickName \n Email: $authToken.email \n Gender: $authToken.gender""")
        }

        log.info("Completed OpenID verification for $authToken.userID")
        return authToken
      }
      log.error("Unable to complete OpenID login, did not successfully verify response " + verification.getStatusMsg())
      return null
    }
    catch (OpenIDException e) {
      log.warn('Unable to complete openID login ' + e.getLocalizedMessage())
      log.debug e.printStackTrace()
      return null
    }
  }
}
