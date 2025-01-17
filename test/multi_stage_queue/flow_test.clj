(ns multi-stage-queue.flow-test
  (:require
   [clojure.core.async :as async]
   [clojure.core.async.flow :as flow]
   [clojure.pprint :as pp]
   [clojure.test :refer [deftest is run-tests]]
   [multi-stage-queue.core :as msq]))

(defn monitoring [{:keys [report-chan error-chan]}]
  (prn "========= monitoring start")
  (async/thread
    (loop []
      (let [[val port] (async/alts!! [report-chan error-chan])]
        (if (nil? val)
          (prn "========= monitoring shutdown")
          (do
            (prn (str "======== message from " (if (= port error-chan) :error-chan :report-chan)))
            (pp/pprint val)
            (recur))))))
  nil)

(def all-stages [:in-memory :local-storage :server])

(defn add-proc [state id stage]
  (let [f (fn [tx]
            (swap! state (fn [st]
                           (update st id msq/add stage tx tx)))
            tx)]
    {:proc (-> f flow/lift1->step flow/step-process)}))

(defn passthrough-proc
  []
  {:proc (-> identity flow/lift1->step flow/step-process)})

(defn null-proc []
   {:proc (flow/process
           {:describe (fn [] {:ins {:in "into the void, or maybe print"}})
            :transform (fn [_ _ v] #_(prn v))})})

(defn state-to-data [state]
  (reduce
   (fn [st k] (update st k msq/to-data))
   state
   (keys state)))

(defn state-to-logs [state]
  (->> state
       state-to-data
       (map (fn [[k v]]
              [k (->> v :log (map first) vec)]))
       (into {})))

;; might need to be changed
;; on repl 50 is enough, but on `clojure -M:dev:test-clj` I've needed up to 2000
(defn sleep []
  (Thread/sleep 2000))

;; from README.md#examples
(deftest concurrency
  (let [state (atom {:server (msq/create-state :server [:server])
                     :alice  (msq/create-state :alice all-stages)
                     :elsa   (msq/create-state :elsa all-stages)
                     :bob    (msq/create-state :bob all-stages)})
        gdef  {:procs
               {:server              (add-proc state :server :server)
                ;; alice
                :alice/in-memory     (add-proc state :alice :in-memory)
                :alice/local-storage (add-proc state :alice :local-storage)
                :alice/upload        (passthrough-proc)
                :alice/download      (passthrough-proc)
                :alice/server        (add-proc state :alice :server)
                ;; bob
                :bob/in-memory       (add-proc state :bob :in-memory)
                :bob/local-storage   (add-proc state :bob :local-storage)
                :bob/server          (add-proc state :bob :server)
                ;; elsa
                :elsa/in-memory      (add-proc state :elsa :in-memory)
                :elsa/local-storage  (add-proc state :elsa :local-storage)
                :elsa/server         (add-proc state :elsa :server)
                ;; sink
                :sink                (null-proc)}
               :conns
               [;; alice
                [[:alice/in-memory :out]     [:alice/local-storage :in]]
                [[:alice/local-storage :out] [:alice/upload :in]]
                [[:alice/upload :out]        [:server :in]]
                [[:server :out]              [:alice/download :in]]
                [[:alice/download :out]      [:alice/server :in]]
                [[:alice/server :out]        [:sink :in]]
                ;; bob
                [[:bob/in-memory :out]       [:bob/local-storage :in]]
                [[:bob/local-storage :out]   [:server :in]]
                [[:server :out]              [:bob/server :in]]
                [[:bob/server :out]          [:sink :in]]
                ;; elsa (shares some with alice)
                [[:elsa/in-memory :out]      [:alice/local-storage :in]]
                [[:alice/in-memory :out]     [:elsa/local-storage :in]]
                [[:alice/download :out]      [:elsa/server :in]]
                [[:elsa/server :out]         [:sink :in]]]}
        g     (flow/create-flow gdef)]
    (flow/start g)
    (flow/resume g)

    ;; elsa isn't around yet
    (flow/pause-proc g :elsa/local-storage)
    (flow/pause-proc g :elsa/server)

    ;; simple
    (flow/inject g [:alice/in-memory :in] ["a1"])
    (sleep)
    (is (= {:server ["a1"]
            :alice  ["a1"]
            :bob    ["a1"]
            :elsa   []}
           (state-to-logs @state)))

    ;; concurrency
    (flow/pause-proc g :alice/upload)
    (flow/pause-proc g :alice/download)
    (flow/inject g [:alice/in-memory :in] ["a2"])
    (flow/inject g [:bob/in-memory :in] ["b1"])
    (sleep)
    (is (= {:server ["b1" "a1"]
            :alice  ["a2" "a1"]
            :bob    ["b1" "a1"]
            :elsa   []}
           (state-to-logs @state)))
    (flow/resume-proc g :alice/upload)
    (flow/resume-proc g :alice/download)
    (sleep)
    (is (= {:server ["a2" "b1" "a1"]
            :alice  ["a2" "b1" "a1"]
            :bob    ["a2" "b1" "a1"]
            :elsa   []}
           (state-to-logs @state)))

    ;; alice is offline
    (flow/pause-proc g :alice/upload)
    (flow/pause-proc g :alice/download)
    (flow/inject g [:alice/in-memory :in] ["a3" "a4" "a5"])
    (flow/inject g [:alice/in-memory :in] ["a6" "a7"])
    (flow/inject g [:bob/in-memory :in] ["b2" "b3" "b4"])
    (sleep)
    (is (= {:server ["b4" "b3" "b2" "a2" "b1" "a1"],
            :alice  ["a7" "a6" "a5" "a4" "a3" "a2" "b1" "a1"]
            :bob    ["b4" "b3" "b2" "a2" "b1" "a1"]
            :elsa   []}
           (state-to-logs @state)))

    ;; two offline alices
    (flow/resume-proc g :elsa/local-storage)
    (flow/resume-proc g :elsa/server)
    (sleep)
    (is (= {:server ["b4" "b3" "b2" "a2" "b1" "a1"],
            :alice  ["a7" "a6" "a5" "a4" "a3" "a2" "b1" "a1"]
            :bob    ["b4" "b3" "b2" "a2" "b1" "a1"]
            :elsa   ["a7" "a6" "a5" "a4" "a3" "a2" "b1" "a1"]}
           (state-to-logs @state)))
    (flow/inject g [:elsa/in-memory :in] ["e1"])
    (sleep)
    (is (= {:server ["b4" "b3" "b2" "a2" "b1" "a1"],
            :alice  ["e1" "a7" "a6" "a5" "a4" "a3" "a2" "b1" "a1"]
            :bob    ["b4" "b3" "b2" "a2" "b1" "a1"]
            :elsa   ["e1" "a7" "a6" "a5" "a4" "a3" "a2" "b1" "a1"]}
           (state-to-logs @state)))

    ;; back online
    (flow/resume-proc g :alice/upload)
    (flow/resume-proc g :alice/download)
    (sleep)
    (is (= {:server ["e1" "a7" "a6" "a5" "a4" "a3" "b4" "b3" "b2" "a2" "b1" "a1"],
            :alice  ["e1" "a7" "a6" "a5" "a4" "a3" "b4" "b3" "b2" "a2" "b1" "a1"]
            :bob    ["e1" "a7" "a6" "a5" "a4" "a3" "b4" "b3" "b2" "a2" "b1" "a1"]
            :elsa   ["e1" "a7" "a6" "a5" "a4" "a3" "b4" "b3" "b2" "a2" "b1" "a1"]}
           (state-to-logs @state)))
    (flow/stop g)))

(comment
  (run-tests))
