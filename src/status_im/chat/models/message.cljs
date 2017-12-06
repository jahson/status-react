(ns status-im.chat.models.message
  (:require [re-frame.core :as re-frame]
            [status-im.constants :as constants]
            [status-im.chat.events.console :as console-events]
            [status-im.chat.events.requests :as requests-events]
            [status-im.chat.models :as chat-model]
            [status-im.chat.models.commands :as commands-model]
            [status-im.chat.models.unviewed-messages :as unviewed-messages-model]
            [status-im.chat.utils :as chat-utils]
            [status-im.data-store.messages :as messages-store]
            [status-im.utils.datetime :as datetime-utils]
            [status-im.utils.clocks :as clocks-utils]
            [taoensso.timbre :as log]
            [status-im.utils.random :as random]))

(defn- get-current-account
  [{:accounts/keys [accounts current-account-id]}]
  (get accounts current-account-id))

(def receive-interceptors
  [(re-frame/inject-cofx :message-exists?)
   (re-frame/inject-cofx :pop-up-chat?)
   (re-frame/inject-cofx :get-last-clock-value)
   (re-frame/inject-cofx :random-id)
   (re-frame/inject-cofx :get-stored-chat)
   re-frame/trim-v])

(defn- lookup-response-ref
  [access-scope->commands-responses account chat contacts response-name]
  (let [available-commands-responses (commands-model/commands-responses :response
                                                                        access-scope->commands-responses
                                                                        account
                                                                        chat
                                                                        contacts)]
    (:ref (get available-commands-responses response-name))))

(defn- add-message-to-db
  [db {:keys [message-id] :as message} chat-id]
  (-> db
      (chat-utils/add-message-to-db chat-id chat-id message (:new? message))
      (unviewed-messages-model/add-unviewed-message chat-id message-id)))

(defn receive
  [{:keys [db message-exists? pop-up-chat? get-last-clock-value now] :as cofx}
   {:keys [from group-id chat-id content-type content message-id timestamp clock-value]
    :as   message
    :or   {clock-value 0}}]
  (let [{:keys [access-scope->commands-responses] :contacts/keys [contacts]} db
        {:keys [public-key] :as current-account} (get-current-account db)
        chat-identifier (or group-id chat-id from)]
    ;; proceed with adding message if message is not already stored in realm,
    ;; it's not from current user (outgoing message) and it's for relevant chat
    ;; (either current active chat or new chat not existing yet)
    (when (and (not (message-exists? message-id))
               (not= from public-key)
               (pop-up-chat? chat-identifier))
      (let [fx               (if (get-in db [:chats chat-identifier])
                               (chat-model/upsert-chat cofx {:chat-id chat-identifier
                                                             :group-chat (boolean group-id)})
                               (chat-model/add-chat cofx chat-identifier))
            command-request? (= content-type constants/content-type-command-request)
            command          (:command content)
            enriched-message (cond-> (assoc message
                                       :chat-id     chat-identifier
                                       :timestamp   (or timestamp now)
                                       :show?       true
                                       :clock-value (clocks-utils/receive
                                                      clock-value
                                                      (get-last-clock-value chat-identifier)))
                                     (and command command-request?)
                                     (assoc-in [:content :content-command-ref]
                                               (lookup-response-ref access-scope->commands-responses
                                                                    current-account
                                                                    (get-in fx [:db :chats chat-identifier])
                                                                    contacts
                                                                    command)))]
        (cond-> (-> fx
                    (update :db add-message-to-db enriched-message chat-identifier)
                    (assoc :save-message (dissoc enriched-message :new?)))
                command-request?
                (requests-events/add-request chat-identifier enriched-message))))))


;;;; Send message

(def send-interceptors
  [(re-frame/inject-cofx :random-id)
   (re-frame/inject-cofx :random-id-seq)
   (re-frame/inject-cofx :get-stored-chat)
   (re-frame/inject-cofx :now)
   re-frame/trim-v])

(defn- prepare-message [clock-value params chat]
  (let [{:keys [chat-id identity message]} params
        {:keys [group-chat public?]} chat
        message {:message-id   (random/id)
                 :chat-id      chat-id
                 :content      message
                 :from         identity
                 :content-type constants/text-content-type
                 :outgoing     true
                 :timestamp    (datetime-utils/now-ms)
                 :clock-value  (clocks-utils/send clock-value)
                 :show?        true}]
    (cond-> message
            (and group-chat public?)
            (assoc :group-id chat-id :message-type :public-group-user-message)
            (and group-chat (not public?))
            (assoc :group-id chat-id :message-type :group-user-message)
            (not group-chat)
            (assoc :to chat-id :message-type :user-message))))

(defn send-message [{{:keys [network-status] :as db} :db
                     :keys [now get-stored-chat]}
                    {:keys [chat-id] :as params}]
  (let [chat        (get-in db [:chats chat-id])
        clock-value (messages-store/get-last-clock-value chat-id)
        message     (prepare-message clock-value params chat)
        params'     (assoc params :message message)

        cofx        {:db                      (chat-utils/add-message-to-db db chat-id chat-id message)
                     :update-message-overhead [chat-id network-status]
                     :save-message            message
                     :dispatch-n              [[:chat-send-message/send-message! params']]}]

    (merge cofx (chat-model/upsert-chat (assoc cofx :get-stored-chat get-stored-chat :now now)
                                        {:chat-id chat-id}))))

(defn- prepare-command
  [identity chat-id clock-value
   {request-params  :params
    request-command :command
    :keys           [prefill prefillBotDb]
    :as             request}
   {:keys [id params command to-message handler-data content-type]}]
  (let [content (if request
                  {:command        request-command
                   :params         (assoc request-params :bot-db (:bot-db params))
                   :prefill        prefill
                   :prefill-bot-db prefillBotDb}
                  {:command (:name command)
                   :scope   (:scope command)
                   :params  params})
        content' (assoc content :handler-data handler-data
                                :type (name (:type command))
                                :content-command (:name command)
                                :content-command-scope-bitmask (:scope-bitmask command)
                                :content-command-ref (:ref command)
                                :preview (:preview command)
                                :short-preview (:short-preview command)
                                :bot (or (:bot command)
                                         (:owner-id command)))]
    {:message-id   id
     :from         identity
     :to           chat-id
     :timestamp    (datetime-utils/now-ms)
     :content      content'
     :content-type (or content-type
                       (if request
                         constants/content-type-command-request
                         constants/content-type-command))
     :outgoing     true
     :to-message   to-message
     :type         (:type command)
     :has-handler  (:has-handler command)
     :clock-value  (clocks-utils/send clock-value)
     :show?        true}))

(defn send-command
  [{{:keys [current-public-key network-status] :as db} :db
    :keys [now get-stored-chat random-id-seq]} result add-to-chat-id params]
  (let [{{:keys [handler-data
                 command]
          :as   content} :command
         chat-id         :chat-id} params
        clock-value   (messages-store/get-last-clock-value chat-id)
        request       (:request handler-data)
        hidden-params (->> (:params command)
                           (filter :hidden)
                           (map :name))
        command'      (prepare-command current-public-key chat-id clock-value request content)
        preview       (get-in db [:message-data :preview (:message-id command')])
        params'       (assoc params :command command')

        cofx          {:db                      (-> (merge db (:db result))
                                                    (chat-utils/add-message-to-db add-to-chat-id chat-id command'))
                       :update-message-overhead [chat-id network-status]
                       :save-message            (cond-> (-> command'
                                                            (assoc :chat-id chat-id)
                                                            (update-in [:content :params]
                                                                       #(apply dissoc % hidden-params))
                                                            (dissoc :to-message :has-handler :raw-input))
                                                  preview
                                                  (assoc :preview (pr-str preview)))
                       :dispatch-n              [[:chat-send-message/send-command! params']]}]

    (cond-> (merge cofx (chat-model/upsert-chat (assoc cofx :get-stored-chat get-stored-chat :now now)
                                                {:chat-id chat-id}))

      (:to-message command')
      (assoc :chat-requests/mark-as-answered {:chat-id    chat-id
                                              :message-id (:to-message command')})

      (= constants/console-chat-id chat-id)
      (as-> cofx'
        (let [messages (console-events/console-respond-command-messages params' random-id-seq)
              events   (mapv #(vector :chat-received-message/add %) messages)]
          (update cofx' :dispatch-n into events))))))

(defn invoke-console-command-handler
  [{:keys [db] :as cofx} {:keys [chat-id command] :as command-params}]
  (let [fx-fn (get console-events/console-commands->fx (-> command :command :name))
        result (fx-fn cofx command)]
    (send-command cofx result chat-id command-params)))

(defn invoke-command-handlers
  [{{:keys          [bot-db]
     :accounts/keys [accounts current-account-id]
     :contacts/keys [contacts] :as db} :db}
   {{:keys [command params id]} :command
    :keys [chat-id address]
    :as orig-params}]
  (let [{:keys [type name scope-bitmask bot owner-id]} command
        handler-type (if (= :command type) :commands :responses)
        to           (get-in contacts [chat-id :address])
        identity     (or owner-id bot chat-id)
        bot-db       (get bot-db (or bot chat-id))
        ;; TODO what's actually semantic difference between `:parameters` and `:context`
        ;; and do we have some clear API for both ? seems very messy and unorganized now
        jail-params  {:parameters params
                      :context    (cond-> {:from            address
                                           :to              to
                                           :current-account (get accounts current-account-id)
                                           :message-id      id}
                                          (:async-handler command)
                                          (assoc :orig-params orig-params))}]
    {:call-jail {:jail-id  identity
                 :path     [handler-type [name scope-bitmask] :handler]
                 :params   jail-params
                 :callback (if (:async-handler command)
                             (fn [_]
                               (log/debug "Async command handler called"))
                             (fn [res]
                               (re-frame/dispatch [:command-handler! chat-id orig-params res])))}}))

(defn process-command
  [{:keys [db] :as cofx} {:keys [command message chat-id] :as params}]
  (let [{:keys [command] :as content} command]
    (-> {:db (chat-model/set-chat-ui-props db {:sending-in-progress? false})}

        (as-> cofx'
          (cond
            (and (= constants/console-chat-id chat-id)
                 (console-events/commands-names (:name command)))
            (invoke-console-command-handler (merge cofx cofx') params)

            (:has-handler command)
            (merge cofx' (invoke-command-handlers (merge cofx cofx') params))

            :else
            (merge cofx' (send-command cofx cofx' chat-id params)))))))
