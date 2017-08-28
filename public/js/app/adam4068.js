/**
 * 
 */

angular.module('adam4068View',[])
.controller('adam4068Ctrl', ['InstConfigService', '$scope', function($config, $scope) {
	function handleConfig(config){
		$scope.channels=[0, 1, 2, 3, 4, 5, 6, 7];
		var index = 0;
		
		if(isEmpty(config.param)){		
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
					desc += "<br/>事件行動<strong>:" + param.ch[i].evtOp + "</strong>";
					desc += "<br/>持續時間:" + param.ch[i].duration + "秒";
				}	
			}
			
			return desc;
		}
		
		config.validate=function(){
			var param = $scope.param;

			if(param.addr.length == 0){
				alert(idx +": 位址是空的!");
				return false;
			}

			for(var i=0;i<8;i++){
				if(param.ch[i].enable){
					try{
						if(param.ch[i].duration.length === 0){
							alert("請指定持續時間");
							return false;
						}
						
						param.ch[i].duration = parseInt(param.ch[i].duration);
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