(ns status-im.chat.events.send-message
  (:require [re-frame.core :as re-frame]
            [status-im.chat.utils :as chat-utils]
            [status-im.chat.models.message :as message-model]
            [status-im.constants :as constants]
            [status-im.data-store.chats :as chats-store]
            [status-im.native-module.core :as status]
            [status-im.protocol.core :as protocol]
            [status-im.utils.config :as config]
            [status-im.utils.handlers :as handlers]
            [status-im.utils.random :as random]
            [status-im.utils.types :as types]
            [status-im.utils.datetime :as datetime]
            [taoensso.timbre :as log]))

;;;; Helper methods

(defn send-notification [fcm-token message]
  (if (and fcm-token config/notifications-wip-enabled?)
    (do (log/debug "send-notification fcm-token: " fcm-token)
        (log/debug "send-notification message: " message)
        (status/notify fcm-token (fn [res]
                                   (log/debug "send-notification cb result: " res))))
    (log/debug "send-notification message not sending because fcm-token is unavailable or notification flag is off")))


(defn handle-message-from-bot [cofx {:keys [message chat-id]}]
  (when-let [message (cond
                       (string? message)
                       {:message-id   (random/id)
                        :content      (str message)
                        :content-type constants/text-content-type
                        :outgoing     false
                        :chat-id      chat-id
                        :from         chat-id
                        :to           "me"}

                       (= "request" (:type message))
                       {:message-id   (random/id)
                        :content      (assoc (:content message) :bot chat-id)
                        :content-type constants/content-type-command-request
                        :outgoing     false
                        :chat-id      chat-id
                        :from         chat-id
                        :to           "me"})]
    (message-model/receive cofx message)))


;;;; Effects

(re-frame/reg-fx
 :update-message-overhead
 (fn [[chat-id network-status]]
   (if (= network-status :offline)
     (chats-store/inc-message-overhead chat-id)
     (chats-store/reset-message-overhead chat-id))))

;;;; Handlers

(handlers/register-handler-fx
  :chat-send-message/process-command
  message-model/send-interceptors
  (fn [cofx [add-to-chat-id params]]
    (message-model/process-command cofx params)))

(handlers/register-handler-fx
  :chat-send-message/from-jail
  [re-frame/trim-v]
  (fn [cofx [{:keys [chat-id message]}]]
    (let [parsed-message (types/json->clj message)]
      (handle-message-from-bot cofx {:message parsed-message
                                     :chat-id chat-id}))))

(handlers/register-handler-fx
  :chat-send-message/send-message!
  [re-frame/trim-v]
  (fn [{:keys          [web3 chats network-status]
        :accounts/keys [accounts current-account-id]
        :contacts/keys [contacts]
        :as            db}
       [{{:keys [message-type]
          :as   message} :message
         chat-id         :chat-id}]]
    (let [{:keys [dapp? fcm-token]} (get contacts chat-id)]
      (if dapp?
        (let [data (get-in db [:local-storage chat-id])]
          (status/call-function!
            {:chat-id    chat-id
             :function   :on-message-send
             :parameters {:message (:content message)}
             :context    {:data data
                          :from current-account-id}}))
        (when message
          (let [message' (select-keys message [:from :message-id])
                payload  (select-keys message [:timestamp :content :content-type
                                               :clock-value :show?])
                payload  (if (= network-status :offline)
                           (assoc payload :show? false)
                           payload)
                options  {:web3    web3
                          :message (assoc message' :payload payload)}]
            (cond
              (= message-type :group-user-message)
              (let [{:keys [public-key private-key]} (chats chat-id)]
                (protocol/send-group-message! (assoc options
                                                :group-id chat-id
                                                :keypair {:public  public-key
                                                          :private private-key})))

              (= message-type :public-group-user-message)
              (protocol/send-public-group-message!
                (let [username (get-in accounts [current-account-id :name])]
                  (assoc options :group-id chat-id
                                 :username username)))

              :else
              (do (protocol/send-message! (assoc-in options
                                                    [:message :to] (:to message)))
                  (send-notification fcm-token (:message options))))))))))

(handlers/register-handler-fx
  :chat-send-message/send-command!
  [re-frame/trim-v]
  (fn [{{:keys [web3 current-public-key chats network-status]
         :accounts/keys [accounts current-account-id]
         :contacts/keys [contacts] :as db} :db :as cofx}
       [{:keys [chat-id command]}]]
    (if (get-in contacts [chat-id :dapp?])
      (when-let [text-message (get-in command [:content :handler-data :text-message])]
        (handle-message-from-bot cofx {:message text-message
                                       :chat-id chat-id}))
      (let [{:keys [public-key private-key]} (chats chat-id)
            {:keys [group-chat public?]} (get-in db [:chats chat-id])

            payload (-> command
                        (select-keys [:content :content-type
                                      :clock-value :show?])
                        (assoc :timestamp (datetime/now-ms)))
            payload (if (= network-status :offline)
                      (assoc payload :show? false)
                      payload)
            options {:web3 web3
                     :message {:from current-public-key
                               :message-id (:message-id command)
                               :payload payload}}]
        (cond
          (and group-chat (not public?))
          (protocol/send-group-message! (assoc options
                                          :group-id chat-id
                                          :keypair {:public public-key
                                                    :private private-key}))

          (and group-chat public?)
          (protocol/send-public-group-message!
            (let [username (get-in accounts [current-account-id :name])]
              (assoc options :group-id chat-id
                             :username username)))

          :else
          (protocol/send-message! (assoc-in options
                                            [:message :to] chat-id)))))))