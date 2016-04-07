/**
 * newInstrument2
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
function getCtrlScope(){		
	return angular.element($("#newCtrl")).scope();
}

angular.module('newInstrumentView', ['ngSanitize'])

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
		 '$log',
		 '$route',
		 '$scope', 
		 function($instModal, $log, $route, $scope){
						
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
		  function($instTypeInfoService, $instConfigService, $instModal, $http, $scope) {			
			
			$scope.instrumentID="";
			
			var instTypeInfoCache=[];
			
			$scope.instrumentTypeInfos = $instTypeInfoService.query(function(){
				instTypeInfoCache = $scope.instrumentTypeInfos;
				console.log(instTypeInfoCache);
			});
			
			$scope.selectedInstTypeId = ""; 
			
			var protocolInfo=[];
			$scope.supportedProtocolInfo= function(){
				for(var i=0;i<instTypeInfoCache.length;i++){
					if(instTypeInfoCache[i].id == $scope.selectedInstTypeId){
						$scope.selectedProtocol = instTypeInfoCache[i].protocolInfo[0].id;
						protocolInfo = instTypeInfoCache[i].protocolInfo;
						return instTypeInfoCache[i].protocolInfo;
					}
				}
			}
			
			$scope.selectedProtocol = "";
			
			function getProtocolDesp(){
				for(var i=0;i<protocolInfo.length;i++){
					if(protocolInfo[i].id == $scope.selectedProtocol)
						return protocolInfo[i].desp;
				}
				
				return "";
			}
			
			$scope.tcpHost = 'localhost';
			$scope.comPort = 1;
			
			$scope.getConfigPage = function(){
				$instConfigService.instrumentType = $scope.selectedInstTypeId;
				
				var tapiInstrument= ['t100', 't200', 't300', 't360', 't400', 't700'];
				if(tapiInstrument.indexOf($scope.selectedInstTypeId)!= -1){					
					return "tapiCfg";
				}else if($scope.selectedInstTypeId === "baseline9000"){
					return "baseline9000Cfg";
				}else if($scope.selectedInstTypeId === "adam4017"){
					return "adam4017Cfg";
				}else if($scope.selectedInstTypeId === "verewa_f701"){
					return "verewaCfg";
				}else
					return "default";
			}
			
			function getInstrumentTypeDesp(){
				for(var i=0;i<instTypeInfoCache.length;i++){
					if(instTypeInfoCache[i].id == $scope.selectedInstTypeId){
						return instTypeInfoCache[i].desp;
					}
				}
			}
			
			$scope.getSummary = function(){
			    var summary = "<strong>儀器ID:</strong>" + $scope.instrumentID + "<br/>";
			    summary += "<strong>儀器種類:</strong>" + getInstrumentTypeDesp() + "<br/>";
			    summary += "<strong>通訊協定:</strong>" + getProtocolDesp() + "<br/>";
			    
			    if($scope.selectedProtocol == 'tcp')			    
			    	summary += "<strong>TCP參數:</strong>" + $scope.tcpHost + "<br/>";
			    else
			    	summary += "<strong>RS232參數:</strong>COM" + $scope.comPort + "<br/>";
			    	
			    summary += "<strong>儀器細部設定:</strong>" + $instConfigService.summary();
			    return summary;
			}
			
			$scope.getParam = function(){ return $instConfigService.param; }
			$scope.validateForm = function(){ return $instConfigService.validate(); }
			
			$instModal.subscribeInstrumentId($scope, function(event, id){
				if(id != ""){
					$http.get("/Instrument/"+id).then(function(response) {
						var inst = response.data;
						$scope.instrumentID = inst._id;
						$scope.selectedInstTypeId = inst.instType;
						$scope.selectedProtocol = inst.protocol.protocol;
						$scope.tcpHost = inst.protocol.host;
						$scope.comPort = inst.protocol.comPort;
						$instConfigService.param = JSON.parse(inst.param);
					}, function(errResponse) {
						console.error('Error while fetching instrument...');
					});
				}else{ //New instrument
					$scope.instrumentID="";
					$scope.selectedInstTypeId = "";
					$scope.selectedProtocol = "";
					$scope.tcpHost = "localhost";
					$scope.comPort = 1;
					$instConfigService.param= {};
				}
			});
			
			$scope.notifyHideModal = $instModal.notifyHideModal; 
		} ])
		
.factory('InstConfigService', [function() {
	var service = {
		instrumentType:"",
		summary:function(){ return "";},
		param:{},
		validate:function() {return true;}	
	};
	
	return service;
} ]);