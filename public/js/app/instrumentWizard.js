/**
 * instrumentWizard
 */
var hasOwnProperty = Object.prototype.hasOwnProperty;

function isEmpty(obj) {
    // null and undefined are "empty"
    if (obj == null) return true;

    // Assume if it has a length property with a non-zero value
    // that that property is correct.
    if (obj.length > 0)    return false;
    if (obj.length === 0)  return true;

    // Otherwise, does it have any properties of its own?
    // Note that this doesn't handle
    // toString and valueOf enumeration bugs in IE < 9
    for (var key in obj) {
        if (hasOwnProperty.call(obj, key)) return false;
    }

    return true;
}

angular.module('newInstrumentView', 
		['ngSanitize',
		 'mgo-angular-wizard']
)

.factory('InstTypeInfoService', [ '$resource', function($resource) {
	return $resource('/InstrumentTypeInfo/:id');
} ])

.factory('InstrumentModalService', ['$rootScope', function($rootScope) {
	return {
        subscribeInstrumentId: function(scope, callback) {
            var handler = $rootScope.$on('InstrumentID', callback);
            scope.$on('$destroy', handler);
        },
        
        subscribeHideModal: function(scope, callback) {
            var handler = $rootScope.$on('HideModal', callback);
            scope.$on('$destroy', handler);
        },

        notifyInstrumentId: function(id) {        	
            $rootScope.$emit('InstrumentID', id);
        },
        
        notifyHideModal: function() {        	
            $rootScope.$emit('HideModal');
        }

    };
} ])
.controller('manageInstrumentCtrl', 
		['InstrumentModalService',
		 '$scope',
		 function($instModal, $scope){
						
			$scope.modalTitle = "";
			$scope.instModal = $instModal;
			$scope.updateInstrument = function(id){				
				$scope.modalTitle= "變更"+id;
				$instModal.notifyInstrumentId(id);
			}
			
			$scope.newInstrument = function(){				
				$scope.modalTitle= "新增儀器";
				$instModal.notifyInstrumentId("");
			}
			
			$instModal.subscribeHideModal($scope, function(){
				$("#newEquipModal").modal("hide");
				var api = oTable.api();
				api.ajax.reload();
			});
		}])
.controller('newInstrumentCtrl',
		[ 'InstTypeInfoService', 
		  'InstConfigService',
		  'InstrumentModalService',
		  '$http',
		  '$scope', 
		  'WizardHandler',
		  function($instTypeInfoService, $instConfigService, $instModal, $http, $scope, WizardHandler) {			
						
			var instTypeInfoCache=[];			
			$scope.instrumentTypeInfos = $instTypeInfoService.query(function(){
				instTypeInfoCache = $scope.instrumentTypeInfos;
			});
			
			//RW
			$scope.param = {};
			$scope.param.selectedInstTypeId = ""; 
			$scope.param.instrumentID="";
			$scope.param.selectedProtocol = "";
			$scope.param.tcpHost = 'localhost';
			$scope.param.comPort = 1;
			
			//Read only
			$scope.currentStep;
			$scope.editMode=false;

			var protocolInfo=[];

			$scope.supportedProtocolInfo= function(){
				for(var i=0;i<instTypeInfoCache.length;i++){
					if(instTypeInfoCache[i].id == $scope.param.selectedInstTypeId){
						$scope.param.selectedProtocol = instTypeInfoCache[i].protocolInfo[0].id;
						protocolInfo = instTypeInfoCache[i].protocolInfo; 
						return instTypeInfoCache[i].protocolInfo;
					}
				}
			}
						
			function getProtocolDesp(){
				for(var i=0;i<protocolInfo.length;i++){
					if(protocolInfo[i].id == $scope.param.selectedProtocol)
						return protocolInfo[i].desp;
				}
				
				return "";
			}
			
			$scope.getConfigPage = function(){
				$instConfigService.instrumentType = $scope.param.selectedInstTypeId;
				
				var tapiInstrument= ['t100', 't200', 't300', 't360', 't400', 't700'];
				if(tapiInstrument.indexOf($scope.param.selectedInstTypeId)!= -1){					
					return "tapiCfg";
				}else if($scope.param.selectedInstTypeId === "baseline9000"){
					return "baseline9000Cfg";
				}else if($scope.param.selectedInstTypeId === "adam4017"){
					return "adam4017Cfg";
				}else if($scope.param.selectedInstTypeId === "verewa_f701"){
					return "verewaCfg";
				}else if($scope.param.selectedInstTypeId === "moxaE1240" ||
						$scope.param.selectedInstTypeId === "moxaE1212"){
					return "e1240Config";
				}else
					return "default";
			}
			
			function getInstrumentTypeDesp(){
				for(var i=0;i<instTypeInfoCache.length;i++){
					if(instTypeInfoCache[i].id == $scope.param.selectedInstTypeId){
						return instTypeInfoCache[i].desp;
					}
				}
			}
			
			$scope.getSummary = function(){
			    var summary = "<strong>儀器ID:</strong>" + $scope.param.instrumentID + "<br/>";
			    summary += "<strong>儀器種類:</strong>" + getInstrumentTypeDesp() + "<br/>";
			    summary += "<strong>通訊協定:</strong>" + getProtocolDesp() + "<br/>";
			    
			    if($scope.param.selectedProtocol == 'tcp')			    
			    	summary += "<strong>TCP參數:</strong>" + $scope.param.tcpHost + "<br/>";
			    else
			    	summary += "<strong>RS232參數:</strong>COM" + $scope.param.comPort + "<br/>";
			    	
			    summary += "<strong>儀器細部設定:</strong>" + $instConfigService.summary();
			    return summary;
			}
			
			$scope.getParam = function(){
				return $instConfigService.param; 
			}
			
			$scope.validateForm = function(){ return $instConfigService.validate(); }
			
			$instModal.subscribeInstrumentId($scope, function(event, id){
				if(id != ""){
					$http.get("/Instrument/"+id).then(function(response) {
						var inst = response.data;
						$scope.param.instrumentID = inst._id;
						$scope.param.selectedInstTypeId = inst.instType;
						$scope.param.selectedProtocol = inst.protocol.protocol;
						$scope.param.tcpHost = inst.protocol.host;
						$scope.param.comPort = inst.protocol.comPort;
						$instConfigService.param = JSON.parse(inst.param);
						$instConfigService.notifyConfigChanged($instConfigService);						
					}, function(errResponse) {
						console.error('Error while fetching instrument...');
					});
					$scope.editMode = true;
					WizardHandler.wizard().goTo(0);
				}else{ //New instrument
					$scope.param.instrumentID="";
					$scope.param.selectedInstTypeId = "";
					$scope.param.selectedProtocol = "";
					$scope.param.tcpHost = "localhost";
					$scope.param.comPort = 1;
					$instConfigService.param= {};
					$instConfigService.notifyConfigChanged($instConfigService);
					$scope.editMode = false;
					WizardHandler.wizard().reset();
				}
			});
			
			$scope.notifyHideModal = $instModal.notifyHideModal; 
			
			//Wizard
			$scope.finishedWizard = function(){
				if(!$scope.validateForm())
					return;
				
			    var newInstrument= {
					_id: $scope.param.instrumentID,
					instType: $scope.param.selectedInstTypeId,
					protocol:{
						protocol:$scope.param.selectedProtocol,
						host:$scope.param.tcpHost,
						comPort:parseInt($scope.param.comPort),
					},
					param:JSON.stringify($scope.getParam()),
					active:true,
					state:"010"
				};
				
			    console.log(newInstrument);
			    
				$http.put("/Instrument", newInstrument)
					.then(function(ret){						
						
						if(ret.data.ok){
							alert("成功");
						}else
							alert("失敗:"+ret.data.msg);
						
						$scope.notifyHideModal();
						},function(error){
							alert("失敗:"+ error);
							$scope.notifyHideModal();
						});
			}
		} ])
		
.factory('InstConfigService', ['$rootScope', function($rootScope) {
	var service = {
		instrumentType:"",
		summary:function(){ return "";},
		param:{},
		validate:function() {return true;},
		subscribeConfigChanged: function(scope, callback) {
            var handler = $rootScope.$on('InstConfigChanged', callback);
            scope.$on('$destroy', handler);
        },
        notifyConfigChanged: function(config) {        	
            $rootScope.$emit('InstConfigChanged', config);
        }
	};
	
	return service;
} ]);