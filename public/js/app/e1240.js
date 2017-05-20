/**
 * 
 */

angular.module('e1240View',[])
.controller('e1240Ctrl', ['InstConfigService', '$scope', function($config, $scope) {
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
					
					if(param.ch[i].repairMode)
						desc += "<br/>維修模式:啟用";
					
					desc += "<br/><strong>測項:" + param.ch[i].mt + "</strong>";
					desc += "<br/>最大值:" + param.ch[i].max;
					desc += "<br/>測項最大值:" + param.ch[i].mtMax;
					desc += "<br/>最小值:" + param.ch[i].min;
					desc += "<br/>測項最小值:" + param.ch[i].mtMin;						
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
						if(param.ch[i].max.length == 0){
							alert("請指定最大值");
							return false;
						}
						if(param.ch[i].mtMax.length == 0){
							alert("請指定測項最大值");
							return false;
						}
						if(param.ch[i].min.length == 0){
							alert("請指定最小值");
							return false;
						}
						if(param.ch[i].mtMin.length == 0){
							alert("請指定測項最小值");
							return false;
						}				
						
						param.ch[i].max = parseFloat(param.ch[i].max);
						param.ch[i].mtMax = parseFloat(param.ch[i].mtMax);
						param.ch[i].min = parseFloat(param.ch[i].min);
						param.ch[i].mtMin = parseFloat(param.ch[i].mtMin);
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