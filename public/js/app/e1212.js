/**
 * 
 */

angular.module('e1212View',[])
.controller('e1212Ctrl', ['InstConfigService', '$scope', function($config, $scope) {
	function handleConfig(config){
		$scope.channels=[0, 1, 2, 3, 4, 5, 6, 7];
		var index = 0;
		
		if(isEmpty(config.param)){
			console.log("config.param is empty...");		
			$scope.param.addr = '0';
			$scope.param.ch=[];
			for(var i=0;i<8;i++){
				$scope.param.ch.push({});
			}
		}else{
			$scope.param=config.param;
		}
			
		config.summary = function(){
			var param = $scope.param;
			var desc="";
			desc += "<br/>位址:" + param.addr;
			for(var i=0;i<8;i++){
				if(param.ch[i].enable){
					desc += "<br/>CH"+i +":啟用";			
					desc += "<br/><strong>測項:" + param.ch[i].mt + "</strong>";
					desc += "<br/>單位刻度:" + param.ch[i].scale;
				}	
			}
			
			return desc;
		}
		
		config.validate=function(){
			var param = $scope.param;

			if(param.addr.length == 0){
				alert(idx +": 位址是空的!");
				return false;
			}else
				param.addr = parseInt(param.addr)

			for(var i=0;i<8;i++){
				if(param.ch[i].enable){
					try{
						if(param.ch[i].scale.length == 0){
							alert("請指定單位刻度");
							return false;
						}
						
						param.ch[i].scale = parseFloat(param.ch[i].scale);
					}catch(ex){
						alert(ex.toString());
						return false;
					}
				}else
					param.ch[i].enable = false;
			}
			
			//copy back
			config.param = param;
			return true;
		}		
	}//End of handleConfig
	
	handleConfig($config);
	$config.subscribeConfigChanged($scope, function(event, config){
		handleConfig(config);
		});
}]);