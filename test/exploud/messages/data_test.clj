(ns exploud.messages.data-test
  (:require [clj-time.core :as time]
            [exploud
             [aws :as aws]
             [onix :as onix]
             [shuppet :as shuppet]
             [tyranitar :as tyr]]
            [exploud.messages.data :refer :all]
            [midje.sweet :refer :all]))

(fact "that preparing a deployment moves to the correct task"
      (start-deployment-preparation {:parameters {:application "application" :environment "environment"}})
      => (contains {:status :success
                    :parameters {:application "application"
                                 :environment "environment"
                                 :phase "preparation"}}))

(fact "that validating the region fails when it is nil"
      (validate-region {:parameters {:region nil}}) => (contains {:status :error}))

(fact "that validating the region works when region is present"
      (validate-region {:parameters {:region "region"}}) => (contains {:status :success}))

(fact "that validating the environment fails when it is nil"
      (validate-environment {:parameters {:environment nil}}) => (contains {:status :error}))

(fact "that validating the environment works when environment is present"
      (validate-environment {:parameters {:environment "environment"}}) => (contains {:status :success}))

(fact "that validating the application fails when it is nil"
      (validate-application {:parameters {:application nil}}) => (contains {:status :error}))

(fact "that validating the application works when application is present"
      (validate-application {:parameters {:application "application"}}) => (contains {:status :success}))

(fact "that validating the user fails when it is nil"
      (validate-user {:parameters {:user nil}}) => (contains {:status :error}))

(fact "that validating the user works when user is present"
      (validate-user {:parameters {:user "user"}}) => (contains {:status :success}))

(fact "that validating the image fails when it is nil"
      (validate-image {:parameters {:new-state {:image-details {:id nil}}}}) => (contains {:status :error}))

(fact "that validating the image works when image is present"
      (validate-image {:parameters {:new-state {:image-details {:id "image"}}}}) => (contains {:status :success}))

(fact "that validating the message fails when it is nil"
      (validate-message {:parameters {:message nil}}) => (contains {:status :error}))

(fact "that validating the message works when message is present"
      (validate-message {:parameters {:message "message"}}) => (contains {:status :success}))

(fact "that getting the Onix metadata fails when there is an error"
      (get-onix-metadata {:parameters {:application "application"}}) => (contains {:status :error})
      (provided
       (onix/application "application") =throws=> (ex-info "Broken" {})))

(fact "that getting the Onix metadata fails when there is no metadata"
      (get-onix-metadata {:parameters {:application "application"}}) => (contains {:status :error})
      (provided
       (onix/application "application") => nil))

(fact "that getting the Onix metadata works"
      (get-onix-metadata {:parameters {:application "application"}}) => (contains {:status :success
                                                                                   :parameters {:application "application"
                                                                                                :new-state {:onix ..onix..}}})
      (provided
       (onix/application "application") => ..onix..))

(fact "that ensuring the Tyranitar hash doesn't go to Tyranitar if the hash is present"
      (ensure-tyranitar-hash {:parameters {:application "application"
                                           :environment "environment"
                                           :new-state {:hash "hash"}}}) => {:status :success
                                                                            :parameters {:application "application"
                                                                                         :environment "environment"
                                                                                         :new-state {:hash "hash"}}})

(fact "that ensuring the Tyranitar hash goes to Tyranitar if the hash isn't present"
      (ensure-tyranitar-hash {:parameters {:application "application"
                                           :environment "environment"
                                           :new-state {}}})
      => {:status :success
          :parameters {:application "application"
                       :environment "environment"
                       :new-state {:hash "last-hash"}}}
      (provided
       (tyr/last-commit-hash "environment" "application") => "last-hash"))

(fact "that ensuring the Tyranitar hash is an error if there's an exception"
      (ensure-tyranitar-hash {:parameters {:application "application"
                                           :environment "environment"
                                           :new-state {}}})
      => (contains {:status :error})
      (provided
       (tyr/last-commit-hash "environment" "application") =throws=> (ex-info "Busted" {})))

(fact "that verifying the Tyranitar hash works"
      (verify-tyranitar-hash {:parameters {:application "application"
                                           :environment "environment"
                                           :new-state {:hash "hash"}}})
      => (contains {:status :success})
      (provided
       (tyr/verify-commit-hash "environment" "application" "hash") => true))

(fact "that verifying the Tyranitar hash is an error if it doesn't match"
      (verify-tyranitar-hash {:parameters {:application "application"
                                           :environment "environment"
                                           :new-state {:hash "hash"}}})
      => (contains {:status :error})
      (provided
       (tyr/verify-commit-hash "environment" "application" "hash") => false))

(fact "that an exception while verifying the Tyranitar hash is an error"
      (verify-tyranitar-hash {:parameters {:application "application"
                                           :environment "environment"
                                           :new-state {:hash "hash"}}})
      => (contains {:status :error})
      (provided
       (tyr/verify-commit-hash "environment" "application" "hash") =throws=> (ex-info "Busted" {})))

(fact "that getting no Tyranitar application properties is an error"
      (get-tyranitar-application-properties {:parameters {:application "application"
                                                          :environment "environment"
                                                          :new-state {:hash "hash"}}})
      => (contains {:status :error})
      (provided
       (tyr/application-properties "environment" "application" "hash") => nil))

(fact "that an error while getting Tyranitar application properties is an error"
      (get-tyranitar-application-properties {:parameters {:application "application"
                                                          :environment "environment"
                                                          :new-state {:hash "hash"}}})
      => (contains {:status :error})
      (provided
       (tyr/application-properties "environment" "application" "hash") =throws=> (ex-info "Busted" {})))

(fact "that getting Tyranitar application properties works"
      (get-tyranitar-application-properties {:parameters {:application "application"
                                                          :environment "environment"
                                                          :new-state {:hash "hash"}}})
      => {:status :success
          :parameters {:application "application"
                       :environment "environment"
                       :new-state {:hash "hash"
                                   :tyranitar {:application-properties {:tyranitar :properties}}}}}
      (provided
       (tyr/application-properties "environment" "application" "hash") => {:tyranitar :properties}))

(fact "that getting no Tyranitar deployment params is an error"
      (get-tyranitar-deployment-params {:parameters {:application "application"
                                                     :environment "environment"
                                                     :new-state {:hash "hash"}}})
      => (contains {:status :error})
      (provided
       (tyr/deployment-params "environment" "application" "hash") => nil))

(fact "that an error while getting Tyranitar deployment params is an error"
      (get-tyranitar-deployment-params {:parameters {:application "application"
                                                     :environment "environment"
                                                     :new-state {:hash "hash"}}})
      => (contains {:status :error})
      (provided
       (tyr/deployment-params "environment" "application" "hash") =throws=> (ex-info "Busted" {})))

(fact "that getting Tyranitar deployment params adds in defaults where a value hasn't been specified"
      (get-in (get-tyranitar-deployment-params {:parameters {:application "application"
                                                             :environment "environment"
                                                             :new-state {:hash "hash"}}}) [:parameters :new-state :tyranitar :deployment-params])
      => (contains {:default-cooldown 10
                    :desired-capacity 23})
      (provided
       (tyr/deployment-params "environment" "application" "hash") => {:desiredCapacity 23}))

(fact "that getting no Tyranitar launch data is an error"
      (get-tyranitar-launch-data {:parameters {:application "application"
                                               :environment "environment"
                                               :new-state {:hash "hash"}}})
      => (contains {:status :error})
      (provided
       (tyr/launch-data "environment" "application" "hash") => nil))

(fact "that an error while getting Tyranitar launch data is an error"
      (get-tyranitar-launch-data {:parameters {:application "application"
                                               :environment "environment"
                                               :new-state {:hash "hash"}}})
      => (contains {:status :error})
      (provided
       (tyr/launch-data "environment" "application" "hash") =throws=> (ex-info "Busted" {})))

(fact "that getting Tyranitar launch data works"
      (get-tyranitar-launch-data {:parameters {:application "application"
                                               :environment "environment"
                                               :new-state {:hash "hash"}}})
      => {:status :success
          :parameters {:application "application"
                       :environment "environment"
                       :new-state {:hash "hash"
                                   :tyranitar {:launch-data {:launch :data}}}}}
      (provided
       (tyr/launch-data "environment" "application" "hash") => {:launch :data}))

(fact "that a missing security group by ID is an error"
      (map-security-group-ids {:parameters {:environment "environment"
                                            :region "region"
                                            :new-state {:tyranitar {:deployment-params {:selected-security-groups ["group-1" "sg-group-2-id"]}}}}})
      => (contains {:status :error})
      (provided
       (aws/security-groups "environment" "region") => [{:group-id "sg-group-1-id"
                                                         :group-name "group-1"}]))

(fact "that a missing security group by name is an error"
      (map-security-group-ids {:parameters {:environment "environment"
                                            :region "region"
                                            :new-state {:tyranitar {:deployment-params {:selected-security-groups ["group-1" "group-2"]}}}}})
      => (contains {:status :error})
      (provided
       (aws/security-groups "environment" "region") => [{:group-id "sg-group-1-id"
                                                         :group-name "group-1"}]))
(fact "that all security groups present is a success"
      (map-security-group-ids {:parameters {:environment "environment"
                                            :region "region"
                                            :new-state {:tyranitar {:deployment-params {:selected-security-groups ["group-1" "sg-group-2-id"]}}}}})
      => (contains {:status :success
                    :parameters {:environment "environment"
                                 :region "region"
                                 :new-state {:selected-security-group-ids ["sg-group-1-id" "sg-group-2-id"]
                                             :tyranitar {:deployment-params {:selected-security-groups ["group-1" "sg-group-2-id"]}}}}})
      (provided
       (aws/security-groups "environment" "region") => [{:group-id "sg-group-1-id"
                                                         :group-name "group-1"}
                                                        {:group-id "sg-group-2-id"
                                                         :group-name "group-2"}]))

(fact "that an exception causes the task to error"
      (map-security-group-ids {:parameters {:environment "environment"
                                            :region "region"
                                            :new-state {:tyranitar {:deployment-params {:selected-security-groups ["group-1" "sg-group-2-id"]}}}}})
      => (contains {:status :error})
      (provided
       (aws/security-groups "environment" "region") =throws=> (ex-info "Busted" {})))

(fact "populating previous state when a previous one doesn't exist succeeds"
      (populate-previous-state {:parameters {:application "application"
                                             :environment "environment"
                                             :region "region"}})
      => {:parameters {:application "application"
                       :environment "environment"
                       :region "region"}
          :status :success}
      (provided
       (aws/last-application-auto-scaling-group "application" "environment" "region") => nil))

(fact "populating previous tyranitar application properties when no previous state exists succeeds"
      (populate-previous-tyranitar-application-properties {:parameters {:application "application"
                                                                        :environment "environment"}})
      => {:parameters {:application "application"
                       :environment "environment"}
          :status :success})

(fact "getting previous image details when no previous state exists succeeds"
      (get-previous-image-details {:parameters {:environment "environment"
                                                :region "region"}})
      => {:parameters {:environment "environment"
                       :region "region"}
          :status :success})

(fact "that creating names for something without any previous state works"
      (create-names {:parameters {:application "application"
                                  :environment "environment"}})
      => (contains {:status :success
                    :parameters {:application "application"
                                 :environment "environment"
                                 :new-state {:launch-configuration-name "application-environment-v001-20140102030405"
                                             :auto-scaling-group-name "application-environment-v001"}}})
      (provided
       (time/now) => (time/date-time 2014 1 2 3 4 5)))

(fact "that checking Shuppet configuration fails when Shuppet configuration doesn't exist"
      (check-shuppet-configuration {:parameters {:application "application"
                                                 :environment "environment"}})
      => (contains {:status :error})
      (provided
       (shuppet/configuration "environment" "application") => nil))

(fact "that checking Shuppet configuration retries when Shuppet throws up"
      (check-shuppet-configuration {:parameters {:application "application"
                                                 :environment "environment"}})
      => (contains {:status :retry})
      (provided
       (shuppet/configuration "environment" "application") =throws=> (ex-info "Busted" {:type :exploud.shuppet/unexpected-response})))

(fact "that checking for deleted load balancers removes previously used load balancers which no longer exist"
      (check-for-deleted-load-balancers {:parameters {:environment "environment"
                                                      :region "region"
                                                      :previous-state {:tyranitar {:deployment-params {:selected-load-balancers ["existing" "nonexistent"]}}}}})
      => {:parameters {:environment "environment"
                       :region "region"
                       :previous-state {:tyranitar {:deployment-params {:selected-load-balancers ["existing"]}}}}
          :status :success}
      (provided
       (aws/load-balancers-with-names "environment" "region" ["existing" "nonexistent"]) => {"existing" {}
                                                                                             "nonexistent" nil}))

(def populate-subnets-params
  {:environment "environment"
   :new-state {:tyranitar {:deployment-params {:subnet-purpose "internal"}}}
   :region "region"})

(fact "that populating subnets uses all subnets found when `selected-zones` has not been given"
      (:new-state (:parameters (populate-subnets {:parameters populate-subnets-params})))
      => (contains {:availability-zones ["regiona" "regionb"]
                    :selected-subnets ["1" "2"]})
      (provided
       (aws/subnets-by-purpose "environment" "region" "internal") => [{:subnet-id "1" :availability-zone "regiona"} {:subnet-id "2" :availability-zone "regionb"}]))

(fact "that populating subnets correctly filters the subnets based on the contents of `availability-zones` when `selected-zones` has been given"
      (:new-state (:parameters (populate-subnets {:parameters (assoc-in populate-subnets-params [:new-state :tyranitar :deployment-params :selected-zones] ["b"])})))
      => (contains {:availability-zones ["regionb"]
                    :selected-subnets ["2"]})
      (provided
       (aws/subnets-by-purpose "environment" "region" "internal") => [{:subnet-id "1" :availability-zone "regiona"} {:subnet-id "2" :availability-zone "regionb"}]))

(fact "that populating subnets gives an error when the subnets cannot match the provided demands of `selected-zones`"
      (populate-subnets {:parameters (assoc-in populate-subnets-params [:new-state :tyranitar :deployment-params :selected-zones] ["a" "c"])})
      => (contains {:status :error})
      (provided
       (aws/subnets-by-purpose "environment" "region" "internal") => [{:subnet-id "1" :availability-zone "regiona"} {:subnet-id "2" :availability-zone "regionb"}]))

(fact "that populating subnets gives an error when no subnets are found"
      (populate-subnets {:parameters (assoc-in populate-subnets-params [:new-state :tyranitar :deployment-params :selected-zones] ["c"])})
      => (contains {:status :error})
      (provided
       (aws/subnets-by-purpose "environment" "region" "internal") => []))
