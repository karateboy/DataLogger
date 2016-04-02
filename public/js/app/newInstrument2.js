/**
 * newInstrument2
 */

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
		 '$scope', 
		 function($instModal, $log, $scope){
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
				console.log($scope);
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
				var tapiInstrument= ['t100', 't200', 't300', 't360', 't400', 't700'];
				if(tapiInstrument.indexOf($scope.selectedInstTypeId)!= -1){
					return "tapiCfg";
				}else if($scope.selectedInstTypeId === "baseline9000"){
					return "baseline9000Cfg";
				}else if($scope.selectedInstTypeId === "adam4017"){
					return "adam4017Cfg";
				}else{
					return "unknown";
				}					
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
					}, function(errResponse) {
						console.error('Error while fetching instrument...');
					});
				}else{ //New instrument
					$scope.instrumentID="";
					$scope.selectedInstTypeId = "";
					$scope.selectedProtocol = "";
					$scope.tcpHost = "localhost";
					$scope.comPort = 1;
				}
			});
			
			$scope.notifyHideModal = $instModal.notifyHideModal; 
		} ])
		
.factory('InstConfigService', [function() {
	var service = {
		summary:function(){ return "";},
		param:{},
		validate:function() {return true;}	
	};
	
	return service;
} ])
		
.controller('adam4017Ctrl', ['InstConfigService', '$scope', function($config, $scope) {
	$scope.channels=[];

	$scope.param={};
	$scope.param.addr = '01';
	$scope.param.ch=[];
	for(var i=0;i<8;i++){
		$scope.channels.push(i);
		$scope.param.ch.push({});
	}
	
	$config.summary = function(){
		var desc="";
		desc += "<br/>位址:" + $scope.param.addr;
		for(var i=0;i<8;i++){
			if($scope.param.ch[i].enable){
				desc += "<br/>CH"+i +":啟用";			
				desc += "<br/><strong>測項:" + $scope.param.ch[i].mt + "</strong>";
				desc += "<br/>最大值:" + $scope.param.ch[i].max;
				desc += "<br/>測項最大值:" + $scope.param.ch[i].mtMax;
				desc += "<br/>最小值:" + $scope.param.ch[i].min;
				desc += "<br/>測項最小值:" + $scope.param.ch[i].mtMin;						
			}	
		}
		
		return desc;
	}
	
	$config.param = $scope.param;
	
	$config.validate=function(){
		console.log("adam 4017 validate...")
		if($scope.param.addr.length == 0){
			alert("位址是空的!");
			return false;
		}

		for(var i=0;i<8;i++){
			if($scope.param.ch[i].enable){
				try{
					if($scope.param.ch[i].max.length == 0){
						alert("請指定最大值");
						return false;
					}
					if($scope.param.ch[i].mtMax.length == 0){
						alert("請指定測項最大值");
						return false;
					}
					if($scope.param.ch[i].min.length == 0){
						alert("請指定最小值");
						return false;
					}
					if($scope.param.ch[i].mtMin.length == 0){
						alert("請指定測項最小值");
						return false;
					}				
					
					$scope.param.ch[i].max = parseFloat($scope.param.ch[i].max);
					$scope.param.ch[i].mtMax = parseFloat($scope.param.ch[i].mtMax);
					$scope.param.ch[i].min = parseFloat($scope.param.ch[i].min);
					$scope.param.ch[i].mtMin = parseFloat($scope.param.ch[i].mtMin);
				}catch(ex){
					alert(ex.toString());
					return false;
				}
			}else
				$scope.param.ch[i].enable = false;
		}
		return true;
	}
}]);

