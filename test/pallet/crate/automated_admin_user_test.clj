(ns pallet.crate.automated-admin-user-test
  (:use pallet.crate.automated-admin-user)
  (:require
   [pallet.build-actions :as build-actions]
   [pallet.action :as action]
   [pallet.context :as context]
   [pallet.core :as core]
   [pallet.crate.automated-admin-user :as automated-admin-user]
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.crate.sudoers :as sudoers]
   [pallet.live-test :as live-test]
   [pallet.phase :as phase]
   [pallet.utils :as utils]
   [clojure.tools.logging :as logging])
  (:use
   clojure.test
   pallet.test-utils
   [pallet.actions :only [user exec-checked-script]]
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]
   [pallet.monad :only [wrap-pipeline]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest automated-admin-user-test
  (testing "with defaults"
    (is (= (first
            (build-actions/build-actions
             {:phase-context "automated-admin-user"}
             (sudoers/install)
             (user "fred" :create-home true :shell :bash)
             (sudoers/sudoers
              {} {} {"fred" {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
             (wrap-pipeline authorize-user-key
               (context/with-phase-context
                 {:kw :authorize-user-key :msg "authorize-user-key"})
               (ssh-key/authorize-key
                "fred" (slurp (pallet.utils/default-public-key-path))))))
           (first
            (build-actions/build-actions
             {}
             (automated-admin-user "fred"))))))

  (testing "with path"
    (is (= (first
            (build-actions/build-actions
             {:phase-context "automated-admin-user"}
             (sudoers/install)
             (user "fred" :create-home true :shell :bash)
             (sudoers/sudoers
              {} {} {"fred" {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
             (wrap-pipeline authorize-user-key
               (context/with-phase-context
                 {:kw :authorize-user-key :msg "authorize-user-key"})
               (ssh-key/authorize-key
                "fred" (slurp (pallet.utils/default-public-key-path))))))
           (first
            (build-actions/build-actions
             {}
             (automated-admin-user
              "fred" (pallet.utils/default-public-key-path)))))))

  (testing "with byte array"
    (is (= (first
            (build-actions/build-actions
             {:phase-context "automated-admin-user"}
             (sudoers/install)
             (user "fred" :create-home true :shell :bash)
             (sudoers/sudoers
              {} {} {"fred" {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
             (wrap-pipeline authorize-user-key
               (context/with-phase-context
                 {:kw :authorize-user-key :msg "authorize-user-key"})
               (ssh-key/authorize-key "fred" "abc"))))
           (first
            (build-actions/build-actions
             {}
             (automated-admin-user "fred" (.getBytes "abc")))))))

  (testing "with default username"
    (let [user-name (. System getProperty "user.name")]
      (is (= (first

              (build-actions/build-actions
               {:phase-context "automated-admin-user"}
               (sudoers/install)
               (user user-name :create-home true :shell :bash)
               (sudoers/sudoers
                {} {} {user-name {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
               (wrap-pipeline authorize-user-key
                 (context/with-phase-context
                   {:kw :authorize-user-key :msg "authorize-user-key"})
                 (ssh-key/authorize-key
                  user-name
                  (slurp (pallet.utils/default-public-key-path))))))
             (first
              (build-actions/build-actions
               {:user (utils/make-user user-name)}
               (automated-admin-user)))))))
  (testing "with session username"
    (let [user-name "fredxxx"]
      (is (= (first
              (build-actions/build-actions
               {:phase-context "automated-admin-user"}
               (sudoers/install)
               (user user-name :create-home true :shell :bash)
               (sudoers/sudoers
                {} {} {user-name {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
               (wrap-pipeline authorize-user-key
                 (context/with-phase-context
                   {:kw :authorize-user-key :msg "authorize-user-key"})
                 (ssh-key/authorize-key
                  user-name
                  (slurp (pallet.utils/default-public-key-path))))))
             (first
              (build-actions/build-actions
               {:user (utils/make-user user-name)}
               (automated-admin-user))))))))

(deftest live-test
  ;; tests a node specific admin user
  (live-test/test-for
   [image (live-test/images)]
   (logging/debugf "automated-admin-user live test: image %s" (pr-str image))
   (live-test/test-nodes
    [compute node-map node-types]
    {:aau
     (core/server-spec
      :phases {:bootstrap (phase/plan-fn
                           (automated-admin-user/automated-admin-user))
               :verify (phase/plan-fn
                        (context/with-phase-context
                          {:kw :automated-admin-user
                           :msg "Check Automated admin user"}
                          (exec-checked-script
                           "is functional"
                           (pipe (echo @SUDO_USER) (grep "fred")))))}
      :count 1
      :node-spec (core/node-spec :image image)
      :environment {:user {:username "fred"}})}
    (is
     (core/lift
      (val (first node-types)) :phase [:verify] :compute compute)))))
