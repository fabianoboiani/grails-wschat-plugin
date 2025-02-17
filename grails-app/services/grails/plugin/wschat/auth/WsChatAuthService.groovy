package grails.plugin.wschat.auth

import grails.plugin.wschat.ChatAuth
import grails.plugin.wschat.ChatAuthChatRole
import grails.plugin.wschat.ChatAuthLogs
import grails.plugin.wschat.ChatBanList
import grails.plugin.wschat.ChatCustomerBooking
import grails.plugin.wschat.ChatLog
import grails.plugin.wschat.ChatPermissions
import grails.plugin.wschat.ChatRole
import grails.plugin.wschat.ChatRoomList
import grails.plugin.wschat.ChatUser
import grails.plugin.wschat.ChatUserProfile
import grails.plugin.wschat.OffLineMessage
import grails.plugin.wschat.WsChatConfService
import grails.plugin.wschat.beans.SignupBean
import grails.transaction.Transactional
import grails.plugin.wschat.beans.ConfigBean


import java.text.SimpleDateFormat

import javax.websocket.Session

@Transactional
class WsChatAuthService extends WsChatConfService   {

	def wsChatMessagingService
	def wsChatUserService
	def wsChatRoomService
	def chatClientListenerService
	def i18nService

	private void verifyOffLine(Session userSession, String username) {
		def chat = ChatUser?.findByUsername(username)
		def pms=OffLineMessage?.findAllByOfflog(chat.offlog)
		pms?.each { aa->
			wsChatMessagingService.sendMsg(userSession,aa?.contents)
		}
		if (pms) {
			pms*.delete()
		}
	}

	/**
	 * Spring security sign up mixed in with chat sign up
	 * @param bean
	 * @param adminUser
     */
	@Transactional
	public void addSecurityUser(SignupBean bean, boolean adminUser=false) {
		String defaultPermission = adminUser ? ChatUser.CHAT_ADMIN : ChatUser.CHAT_USER
		String springSecurityPermission = adminUser ? ChatUser.SPRINGSECURITY_ADMIN : ChatUser.SPRINGSECURITY_USER
		if (defaultPermission && springSecurityPermission) {
			addUser(bean.username, bean.email, defaultPermission)
			addSecurity(bean.username,bean.password, springSecurityPermission)
		}
	}

	/**
	 * strictly spring security signup
	 * @param username
	 * @param password
	 * @param springSecurityPermission
     */
	@Transactional
	public void addSecurity(String username, String password, String springSecurityPermission=null) {
		if (!springSecurityPermission) {
			springSecurityPermission = ChatUser.SPRINGSECURITY_USER
		}

		def role = ChatRole.findByAuthority(springSecurityPermission)
		if (!role) {
			role = new ChatRole(springSecurityPermission).save()
		}
		def user = new ChatAuth(username, password).save()
		ChatAuthChatRole.create user, role, true
	}

	@Transactional
	public ChatPermissions addPermission(String defaultPermission=null) {
		if (!defaultPermission) {
			defaultPermission = config.defaultperm ?: ChatUser.DEFAULT_PERMISSION
		}
		ChatPermissions perm
		if (defaultPermission) {
			perm = ChatPermissions.findByName(defaultPermission)
			if (!perm) {
				perm = new ChatPermissions(name: defaultPermission).save(flush: true)
			}
		}
		return perm
	}

	@Transactional
	Map addUser(String username, String email=null, String defaultPermission=null) {
		ChatUser user
		ChatPermissions perm
		if (!defaultPermission) {
			defaultPermission = config.defaultperm ?: ChatUser.DEFAULT_PERMISSION
		}
		if (defaultPermission) {
			perm = addPermission(defaultPermission)
			user = ChatUser.findByUsername(username)
			if (!user) {
				def addlog = addLog()
				user = new ChatUser(username: username, permissions: perm, log: addlog, offlog: addlog).save(flush: true)
			}
			if (email) {
				ChatUserProfile.findOrSaveWhere(chatuser: user, email: email).save(flush: true)
			}
		}
		return [user:user, perm:perm]
	}

	@Transactional
	private ChatLog addLog() {
		ChatLog logInstance = new ChatLog(messages: [])
		if (!logInstance.save(flush:true)) {
			log.debug "${logInstance.errors}"
		}
		return logInstance
	}

	@Transactional
	Map validateLogin(String username) {
		def au=addUser(username)
		if (au) {
			ChatUser user = au.user
			ChatPermissions perm = au.perm
			ChatAuthLogs logit = new ChatAuthLogs(username: username, loggedIn: true, loggedOut: false).save()
			if (!logit) {
				log.error "${logit.errors}"
			} else {
				log.debug "${logit.id} ${username} added to ChatAuthLogs: loggedIn"
			}
			[permission: perm?.name ?: ChatUser.DEFAULT_PERMISSION, user: user]
		} else {
			[permission: ChatUser.DEFAULT_PERMISSION,user:username]
		}

	}

	@Transactional
	void validateLogOut(String username) {
		def logit = new ChatAuthLogs(username:username, loggedIn:false,loggedOut:true).save()
		if (!logit) {
			log.error "${logit.errors}"
		} else {
			log.debug "${logit.id} ${username} added to ChatAuthLogs: LoggedOut"
		}

	}

	void delBotFromChatRoom(String username, String roomName, String userType, String message) {
		ConfigBean bean = new ConfigBean()
		String botUser = roomName+"_"+bean.assistant
		boolean addBot = true
		if (userType==ChatRoomList.DEFAULT_ROOM_TYPE) {
			addBot = bean.enable_Chat_Bot
		}
		if (isBotinRoom(botUser)  && addBot) {
			Session currentSession = getChatUser(botUser, roomName)
			if (currentSession) {
				wsChatMessagingService.messageUser(currentSession, [message:"<span class='roomPerson'>${username}: </span><span class='roomMessage'>${message.replaceAll("\\<.*?>","")}</span>"])
				//wsChatMessagingService.messageUser(currentSession, [message:"${username}: ${message}"])
			}
		}
	}
	void addBotToChatRoom(String roomName, String userType, boolean addBot=null, String message=null, String uri=null, String user=null) {
		ConfigBean bean = new ConfigBean()
		if (!message) {
			message = bean.botMessage
		}
		if (!uri) {
			uri = bean.uri
		}
		if (!addBot) {
			addBot = bean.enable_Chat_Bot
		}
		String botUser = roomName+"_"+bean.assistant
		if (!isBotinRoom(botUser)  && addBot) {
			Session currentSession = chatClientListenerService.p_connect(uri, botUser, roomName)
			Boolean userExists=false
			if (user) {
				def cc = ChatCustomerBooking.findByUsername(user)
				if (cc && cc?.name) {
					userExists=true
				}
			}
			if (bean.liveChatAskName && userType==ChatUser.CHAT_LIVE_USER && userExists==false) {
				message+= "\n"+bean.liveChatNameMessage
			}
			log.debug "${message}"
			chatClientListenerService.sendDelayedMessage(currentSession, message,1000)
		}
	}

	Boolean isBotinRoom(String botUser) {
		boolean found = false
		chatNames?.each { String cuser, Map<String,Session> records ->
			if (cuser == botUser) {
				found = true
			}
		}
		return found
	}

	void connectUser(String message,Session userSession,String room, Boolean sendUsers=true) {
		def myMsg = [:]
		Boolean isuBanned = false
		String connector = "CONN:-"
		def user
		def username = message.substring(message.indexOf(connector)+connector.length(),message.length()).trim().replace(' ', '_').replace('.', '_')
		userSession.userProperties.put("username", username)
		isuBanned = isBanned(username)
		if (isuBanned){
			def msga=i18nService.msg("wschat.user.banned","user ${username} is banned being disconnected",[username])
			wsChatMessagingService.messageUser(userSession,[isBanned:msga])
			return
		}
		def userRec = validateLogin(username)
		if (userRec.user) {
			def userLevel = userRec.permission
			user = userRec.user
			userSession.userProperties.put("userLevel", userLevel)
			String rooma = userSession?.userProperties?.get("room")
			if (loggedIn(username) == false) {
				chatroomUsers.putIfAbsent(username, ["${room}": userSession])
			} else {
				Map<String, Session> records = chatroomUsers.get(username)
				Session crec = records.find { it.key == room }?.value
				if (crec) {
					def msga = i18nService.msg("wschat.user.already.loggedin", "${username} is already loggged in to ${room}, action denied", [username, room])
					wsChatMessagingService.messageUser(userSession, [message: msga])
					return
				} else {
					records << ["${room}": userSession]
				}
			}
			Boolean useris = isAdmin(userSession)
			wsChatMessagingService.messageUser(userSession, ["isAdmin": useris as String])
			def myMsg2 = [:]
			myMsg2.put("currentRoom", "${room}")
			wsChatMessagingService.messageUser(userSession, myMsg2)
			wsChatUserService.sendUsers(userSession, username, room)
			String sendjoin = config.send.joinroom ?: 'yes'
			wsChatRoomService.sendRooms(userSession)
			if (useris) {
				def adminActions = [[actions: 'viewUsers'], [actions: 'liveChatsRooms'], [actions: 'createConference'], [actions: 'viewLiveChats',]]
				wsChatMessagingService.broadcast(userSession, [adminOptions: adminActions])
			}
			if (sendjoin == 'yes' && sendUsers) {
				def msga = i18nService.msg("wschat.user.joined", "${username} has joined ${room}", [username, room])
				wsChatMessagingService.broadcast(userSession, [message: msga])
			}
			verifyOffLine(userSession, username)
		}
	}

	@Transactional
	Boolean isBanned(String username) {
		Boolean yesis = false
		def now = new Date()
		def current  =  new SimpleDateFormat('EEE, d MMM yyyy HH:mm:ss').format(now)
		def found = ChatBanList.findAllByUsernameAndPeriodGreaterThan(username,current)
		if (found) {
			yesis = true
		}
		return yesis
	}

	Boolean loggedIn(String user) {
		return chatUserExists(user)
	}
}
